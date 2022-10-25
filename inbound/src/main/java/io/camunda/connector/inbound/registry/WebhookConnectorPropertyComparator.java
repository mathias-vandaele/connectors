/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.camunda.connector.inbound.registry;

import io.camunda.connector.inbound.webhook.WebhookConnectorProperties;

import java.util.Comparator;

public class WebhookConnectorPropertyComparator implements Comparator<WebhookConnectorProperties> {

    @Override
    public int compare(WebhookConnectorProperties o1, WebhookConnectorProperties o2) {
        if (o1==null || o1.getBpmnProcessId()==null) {
            return 1;
        }
        if (o2==null || o2.getBpmnProcessId()==null) {
            return -1;
        }
        if (!o1.getBpmnProcessId().equals(o2.getBpmnProcessId())) {
            return o1.getBpmnProcessId().compareTo(o2.getBpmnProcessId());
        }
        return Integer.compare(o1.getVersion(), o2.getVersion());
    }

}