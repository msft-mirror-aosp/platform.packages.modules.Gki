/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gki.tests;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.io.FileMatchers.aFileWithSize;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.junit.Assert.fail;

import static java.util.stream.Collectors.toList;

import android.cts.host.utils.DeviceJUnit4ClassRunnerWithParameters;
import android.cts.host.utils.DeviceJUnit4Parameterized;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.ApexInfo;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Parameterized.UseParametersRunnerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

@RunWith(DeviceJUnit4Parameterized.class)
@UseParametersRunnerFactory(DeviceJUnit4ClassRunnerWithParameters.RunnerFactory.class)
public class GkiInstallTest extends BaseHostJUnit4Test {

    // Keep in sync with gki.go.
    private static final String HIGH_SUFFIX = "_test_high.apex";
    private static final String LOW_SUFFIX = "_test_low.apex";
    private static final long TEST_HIGH_VERSION = 1000000000L;

    // Timeout between device online for adb commands and boot completed flag is set.
    private static final long BOOT_COMPLETE_TIMEOUT_MS = 180000; // 3mins
    // Timeout for `adb install`.
    private static final long INSTALL_TIMEOUT_MS = 600000; // 10mins

    @Parameter
    public String mFileName;

    private String mPackageName;
    private File mApexFile;
    private boolean mExpectInstallSuccess;

    @Parameters(name = "{0}")
    public static Iterable<String> getTestFileNames() {
        try (Scanner scanner = new Scanner(
                GkiInstallTest.class.getClassLoader().getResourceAsStream(
                        "gki_install_test_file_list.txt"))) {
            List<String> list = new ArrayList<>();
            scanner.forEachRemaining(list::add);
            return list;
        }
    }

    @Before
    public void setUp() throws Exception {
        // Infer package name from file name.
        if (mFileName.endsWith(HIGH_SUFFIX)) {
            mPackageName = mFileName.substring(0, mFileName.length() - HIGH_SUFFIX.length());
            mExpectInstallSuccess = true;
        } else if (mFileName.endsWith(LOW_SUFFIX)) {
            mPackageName = mFileName.substring(0, mFileName.length() - LOW_SUFFIX.length());
            mExpectInstallSuccess = false;
        } else {
            fail("Unrecognized test data file: " + mFileName);
        }

        CLog.i("Wait for device to boot complete for " + BOOT_COMPLETE_TIMEOUT_MS + " ms...");
        assertTrue("Device did not come up after " + BOOT_COMPLETE_TIMEOUT_MS + " ms",
                getDevice().waitForBootComplete(BOOT_COMPLETE_TIMEOUT_MS));

        // Skip if the device does not support this APEX package.
        CLog.i("Checking if " + mPackageName + " is installed on the device.");
        ApexInfo oldApexInfo = getGkiApexInfo();
        assumeThat(oldApexInfo, is(notNullValue()));
        assumeThat(oldApexInfo.name, is(mPackageName));

        // Find the APEX file.
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(getBuild());
        mApexFile = buildHelper.getTestFile(mFileName);

        // There may be empty .apex files in the directory for disabled APEXes. But if the device
        // is known to install the package, the test must be built with non-empty APEXes for this
        // particular package.
        assertThat("Test is not built properly. It does not contain a non-empty " + mFileName,
                mApexFile, is(aFileWithSize(greaterThan(0L))));
    }

    @Test
    public void testInstallAndReboot() throws Exception {
        CLog.i("Installing " + mApexFile + " with " + INSTALL_TIMEOUT_MS + " ms timeout");
        String result = getDevice().installPackage(mApexFile, false,
                "--staged-ready-timeout", String.valueOf(INSTALL_TIMEOUT_MS));
        if (!mExpectInstallSuccess) {
            assertNotNull("Should not be able to install downgrade package", result);
            assertThat(result, containsString("Downgrade of APEX package " + mPackageName +
                    " is not allowed."));
            return;
        }

        assertNull("Installation failed with " + result, result);
        getDevice().reboot();

        CLog.i("Wait for device to boot complete for " + BOOT_COMPLETE_TIMEOUT_MS + " ms...");
        assertTrue("Device did not come up after " + BOOT_COMPLETE_TIMEOUT_MS + " ms",
                getDevice().waitForBootComplete(BOOT_COMPLETE_TIMEOUT_MS));

        ApexInfo newApexInfo = getGkiApexInfo();
        assertNotNull(newApexInfo);
        assertThat(newApexInfo.versionCode, is(TEST_HIGH_VERSION));
    }

    // Reboot device no matter what to avoid interference.
    @After
    public void tearDown() throws Exception {
        getDevice().reboot();
    }

    /**
     * @return The {@link ApexInfo} of the GKI APEX named {@code mPackageName} on the device, or
     * {@code null} if the device does not have a GKI APEX installed.
     * @throws Exception an error has occurred.
     */
    private ApexInfo getGkiApexInfo() throws Exception {
        assertNotNull(mPackageName);
        List<ApexInfo> list = getDevice().getActiveApexes().stream().filter(
                apexInfo -> mPackageName.equals(apexInfo.name)).collect(toList());
        if (list.isEmpty()) return null;
        assertThat(list.size(), is(1));
        return list.get(0);
    }
}
