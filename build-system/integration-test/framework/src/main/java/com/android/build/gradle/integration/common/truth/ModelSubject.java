/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.build.gradle.integration.common.truth;

import static com.google.common.truth.Truth.assertAbout;

import com.android.annotations.NonNull;
import com.android.builder.model.AndroidProject;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.Subject;

/**
 * Truth support for AndroidProject.
 */
public class ModelSubject extends Subject<ModelSubject, AndroidProject> {

    public static Subject.Factory<ModelSubject, AndroidProject> models() {
        return ModelSubject::new;
    }

    public static ModelSubject assertThat(AndroidProject androidProject) {
        return assertAbout(models()).that(androidProject);
    }

    public ModelSubject(@NonNull FailureMetadata failureMetadata, @NonNull AndroidProject subject) {
        super(failureMetadata, subject);
    }
}
