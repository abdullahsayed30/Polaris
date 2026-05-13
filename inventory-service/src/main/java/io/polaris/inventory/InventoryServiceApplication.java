package io.polaris.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "io.polaris.inventory.api",
        "io.polaris.inventory.application",
        "io.polaris.inventory.config",
        "io.polaris.inventory.messaging",
        "io.polaris.inventory.persistence"
})
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
