package com.autoscript.studio

import com.autoscript.core.model.RuntimeConnectionPhase
import com.autoscript.core.model.RuntimeConnectionState
import com.autoscript.core.model.RuntimeEngineState
import org.junit.Assert.*
import org.junit.Test

class VisionProviderPresentationTest {
    @Test fun loadedIsNotRecognitionSuccessAndBundledIsNotExternalSelection() {
        val bundled = requireNotNull(VisionProviderStatus.parse("AVAILABLE,source=bundled,loaded=false"))
        assertFalse(bundled.loaded); assertEquals("bundled", bundled.source)
        assertTrue(bundled.label.contains("未代表"))
        val installed = requireNotNull(VisionProviderStatus.parse("INSTALLED,source=provider,loaded=false"))
        assertEquals("已校验 · 尚未加载", installed.label)
        assertEquals("加载或初始化失败", requireNotNull(VisionProviderStatus.parse("LOAD_FAILED,source=provider,loaded=true")).label)
    }
    @Test fun unknownAndUnboundedRemoteValuesNeverBecomeUiText() {
        for (raw in listOf(null, "AVAILABLE", "secret-path,source=provider,loaded=true", "AVAILABLE,source=unexpected,loaded=true",
            "AVAILABLE,source=provider,loaded=1", "AVAILABLE,source=provider,loaded=true,extra=secret", "X".repeat(129))) {
            assertNull(VisionProviderStatus.parse(raw))
        }
    }
    @Test fun mutationRequiresConfirmedConnectionAndNoActiveRun() {
        val connected = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = -7)
        for (state in listOf(RuntimeEngineState.IDLE, RuntimeEngineState.STOPPED, RuntimeEngineState.FAILED)) assertTrue(canManageVisionProviders(connected.copy(engineState = state)))
        for (state in listOf(RuntimeEngineState.RUNNING, RuntimeEngineState.PAUSED, RuntimeEngineState.STOPPING)) assertFalse(canManageVisionProviders(connected.copy(engineState = state)))
        assertFalse(canManageVisionProviders(RuntimeConnectionState()))
        assertFalse(canManageVisionProviders(connected.copy(sessionGeneration = 0, engineState = RuntimeEngineState.IDLE)))
    }
    @Test fun rapidRunRoundTripAndSessionReplacementInvalidateMutation() {
        val idle = RuntimeConnectionState(phase = RuntimeConnectionPhase.CONNECTED, sessionGeneration = -7, engineState = RuntimeEngineState.IDLE, stateRevision = 12)
        val context = VisionProviderMutationContext(idle)
        assertTrue(context.matches(idle))
        assertFalse(context.matches(idle.copy(stateRevision = 14)))
        assertFalse(context.matches(idle.copy(sessionGeneration = -8)))
        assertFalse(context.matches(idle.copy(engineState = RuntimeEngineState.RUNNING)))
    }
}
