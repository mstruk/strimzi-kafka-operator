/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test.k8s.cluster;

import io.strimzi.test.executor.Exec;
import io.strimzi.test.k8s.cmdClient.KubeCmdClient;
import io.strimzi.test.k8s.cmdClient.Kubectl;
import io.strimzi.test.k8s.exceptions.KubeClusterException;

public class Kind implements KubeCluster {

    public static final String CMD = "kubectl";

    @Override
    public boolean isAvailable() {
        return Exec.isExecutableOnPath(CMD);
    }

    @Override
    public boolean isClusterUp() {
        try {
            return Exec.exec("kubectl", "cluster-info").exitStatus();
        } catch (KubeClusterException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public KubeCmdClient defaultCmdClient() {
        return new Kubectl();
    }
}
