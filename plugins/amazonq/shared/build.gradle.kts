// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("toolkit-jvm-conventions")
}

dependencies {
    implementation(project(":plugin-amazonq:shared:jetbrains-community"))
    implementation(project(":plugin-amazonq:shared:jetbrains-ultimate"))
}
