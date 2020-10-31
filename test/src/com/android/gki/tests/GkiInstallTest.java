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
import static org.hamcrest.io.FileMatchers.aFileWithSize;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import static java.util.stream.Collectors.toSet;

import android.cts.host.utils.DeviceJUnit4ClassRunnerWithParameters;
import android.cts.host.utils.DeviceJUnit4Parameterized;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.tradefed.device.ITestDevice;
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

    private static final String HIGH_SUFFIX = "_test_high.apex";
    private static final String LOW_SUFFIX = "_test_low.apex";

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

        // Skip if the device does not support this APEX package.
        ITestDevice device = getDevice();
        Set<String> installedApexes =
                device.getActiveApexes().stream().map(apexInfo -> apexInfo.name).collect(toSet());
        assumeThat(installedApexes, hasItem(mPackageName));

        // Find the APEX file.
        CompatibilityBuildHelper buildHelper = new CompatibilityBuildHelper(getBuild());
        mApexFile = buildHelper.getTestFile(mFileName);

        // There may be empty .apex files in the directory for disabled APEXes. But if the device
        // is known to install the package, the test must be built with non-empty APEXes for this
        // particular package.
        assertThat(mApexFile, is(aFileWithSize(greaterThan(0L))));
    }

    @Test
    public void testInstallAndReboot() throws Exception {
        String result = getDevice().installPackage(mApexFile, false);
        if (mExpectInstallSuccess) {
            assertNull("Installation failed with " + result, result);
        } else {
            assertNotNull("Should not be able to install downgrade package", result);
            assertThat(result, containsString("Downgrade of APEX package " + mPackageName +
                    " is not allowed."));
        }
    }

    // Reboot device no matter what to avoid interference.
    @After
    public void tearDown() throws Exception {
        getDevice().reboot();
    }
}
