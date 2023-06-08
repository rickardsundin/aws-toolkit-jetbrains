// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.explorer.nodes

import com.intellij.openapi.project.Project
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.CodeWhispererCodeScanManager
import java.awt.event.MouseEvent

class RunCodeScanNode(nodeProject: Project) : CodeWhispererActionNode(
    nodeProject,
    CodeWhispererCodeScanManager.getInstance(nodeProject).getActionButtonText(),
    2,
    CodeWhispererCodeScanManager.getInstance(nodeProject).getActionButtonIconForExplorerNode()
) {

    private val codeScanManager = CodeWhispererCodeScanManager.getInstance(project)

    override fun onDoubleClick(event: MouseEvent) {
        if (codeScanManager.isCodeScanInProgress()) {
            codeScanManager.stopCodeScan()
        } else {
            codeScanManager.runCodeScan()
        }
    }
}
