package dev.warasugi.warp.web;

import io.javalin.Javalin;

public interface RouteRegistrar {
    void register(Javalin app);
}
