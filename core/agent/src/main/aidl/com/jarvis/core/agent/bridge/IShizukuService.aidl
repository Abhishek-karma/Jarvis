package com.jarvis.core.agent.bridge;

interface IShizukuService {
    void destroy() = 16777114;
    void exit() = 1;
    String executeCommand(String command) = 2;
}
