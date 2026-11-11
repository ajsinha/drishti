/*
 * Copyright (c) the publisher of ${artifactId}. All rights reserved.
 */
package ${package};

import com.ash.drishti.kernel.api.module.ModuleApi;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The module's REST API, mounted by the kernel under its namespace: {@code GET /api/v1/${artifactId}/greeting/{name}}. */
@RestController
@ModuleApi("${artifactId}")
public class GreetingController {

    private final Greeting greeting;

    public GreetingController(Greeting greeting) {
        this.greeting = greeting;
    }

    @GetMapping("/greeting/{name}")
    public Map<String, Object> greet(@PathVariable("name") String name) {
        return Map.of("name", name, "greeting", greeting.answer(name));
    }
}
