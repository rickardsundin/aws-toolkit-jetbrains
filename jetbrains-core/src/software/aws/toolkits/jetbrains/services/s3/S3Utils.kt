// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.s3

import software.aws.toolkits.core.region.AwsRegion

fun bucketArn(bucketName: String, region: AwsRegion) = "arn:${region.partitionId}:s3:::$bucketName"

const val NOT_VERSIONED_VERSION_ID = "null"
