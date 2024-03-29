/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.ant;

import com.android.manifmerger.ManifestMerger;
import com.android.manifmerger.MergerLog;
import com.android.sdklib.StdSdkLog;
import com.android.sdklib.io.FileOp;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.Path;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ManifestMergerTask extends SingleDependencyTask {

    private String mAppManifest;
    private String mOutManifest;

    private ArrayList<Path> mLibraryPaths;
    private boolean mEnabled = false;

    public void setAppManifest(Path appManifest) {
        mAppManifest = TaskHelper.checkSinglePath("appManifest", appManifest);
    }

    public void setOutManifest(Path outManifest) {
        mOutManifest = TaskHelper.checkSinglePath("outManifest", outManifest);
    }

    public void setEnabled(boolean enabled) {
        mEnabled  = enabled;
    }

    /**
     * Returns an object representing a nested <var>library</var> element.
     */
    public Object createLibrary() {
        if (mLibraryPaths == null) {
            mLibraryPaths = new ArrayList<Path>();
        }

        Path path = new Path(getProject());
        mLibraryPaths.add(path);

        return path;
    }

    @Override
    public void execute() throws BuildException {
        if (mAppManifest == null) {
            throw new BuildException("Missing attribute appManifest");
        }
        if (mOutManifest == null) {
            throw new BuildException("Missing attribute outManifest");
        }

        // if we merge, then get the rest of the input paths.
        List<File> libraries = new ArrayList<File>();
        if (mLibraryPaths != null) {
            for (Path pathList : mLibraryPaths) {
                for (String path : pathList.list()) {
                    libraries.add(new File(path));
                }
            }
        }

        // prepare input files
        ArrayList<File> allInputs = new ArrayList<File>(libraries.size() + 1);

        // always: the input manifest.
        File appManifestFile = new File(mAppManifest);
        allInputs.add(appManifestFile);

        // if enabled: add the libraries
        if (mEnabled) {
            allInputs.addAll(libraries);
        }

        // figure out the path to the dependency file.
        String depFile = mOutManifest + ".d";

        // get InputPath with no extension restrictions
        List<InputPath> inputPaths = getInputPaths(allInputs, null /*extensionsToCheck*/,
                null /*factory*/);

        if (initDependencies(depFile, inputPaths) && dependenciesHaveChanged() == false) {
            System.out.println(
                    "No changes in the AndroidManifest files.");
            return;
        }

        System.out.println("Merging AndroidManifest files into one.");

        if (mEnabled == false || libraries.size() == 0) {
            if (mEnabled == false) {
                System.out.println("Manifest merger disabled. Using project manifest only.");
            } else {
                System.out.println("No libraries. Using project manifest only.");
            }
            // no merge (disabled or nothing to merge)? do a simple copy.
            try {
                new FileOp().copyFile(appManifestFile, new File(mOutManifest));
            } catch (IOException e) {
                throw new BuildException(e);
            }
        } else {
            System.out.println(String.format("Merging manifests from project and %d libraries.",
                    libraries.size()));
            ManifestMerger merger = new ManifestMerger(MergerLog.wrapSdkLog(new StdSdkLog()));
            if (merger.process(
                    new File(mOutManifest),
                    appManifestFile,
                    libraries.toArray(new File[libraries.size()])) == false) {
                throw new BuildException();
            }
        }

        // generate the dependency file.
        generateDependencyFile(depFile, inputPaths, mOutManifest);
    }

    @Override
    protected String getExecTaskName() {
        return "ManifestMerger";
    }
}
