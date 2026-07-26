package com.github.dropguard.loom;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

import org.jboss.logging.Logger;

@QuarkusMain
public class GatewayApplication implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(GatewayApplication.class);

    @Override
    public int run(String... args) {
        LOG.info("Java Gateway started - Virtual Thread Architecture");
        Quarkus.waitForExit();
        return 0;
    }

    public static void main(String[] args) {
        Quarkus.run(GatewayApplication.class, args);
    }
}
