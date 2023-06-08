// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.explorer.nodes

import com.intellij.openapi.project.Project
import software.amazon.awssdk.services.cloudformation.CloudFormationClient
import software.aws.toolkits.jetbrains.services.cloudformation.CloudFormationServiceNode

class CloudFormationExplorerRootNode : AwsExplorerServiceNode {
    override val serviceId: String = CloudFormationClient.SERVICE_NAME
    override fun buildServiceRootNode(project: Project): AwsExplorerNode<*> = CloudFormationServiceNode(project, this)
}
