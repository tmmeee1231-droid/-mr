package com.manw.tiktoksync;

interface IAppHiderService {
    String setPackageEnabled(String packageName, boolean enabled);
    void destroy() = 16777114;
}