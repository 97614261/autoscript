return function()
    local delay = RunnerConfig.delayMs
    if RunnerConfig.mode == "快速" then delay = math.floor(delay / 2) end
    Task.sleep(delay)
end
