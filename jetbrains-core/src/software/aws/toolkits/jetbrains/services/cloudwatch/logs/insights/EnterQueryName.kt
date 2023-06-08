// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.aws.toolkits.jetbrains.services.cloudwatch.logs.insights

import javax.swing.JPanel
import javax.swing.JTextField

class EnterQueryName {
    lateinit var queryName: JTextField
        private set
    lateinit var saveQueryPanel: JPanel
        private set
}
