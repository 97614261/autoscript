package com.autoscript.runtime.api;

interface IRuntimeStateListener {
    void onRuntimeStateChanged(long sessionGeneration, int stateCode, int rootStateCode, String diagnostic);
    void onExecutionPosition(long sessionGeneration, long runRevision, String projectId, String flowId, String nodeId, long sampleSequence, in String[] recentFlows, in String[] recentNodes);
}
