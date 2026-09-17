package com.mk.healthbar.client

import net.fabricmc.api.ClientModInitializer

class HealthBarClient : ClientModInitializer {
    override fun onInitializeClient() {
        // Health is read live from entities, no tick needed
    }
}