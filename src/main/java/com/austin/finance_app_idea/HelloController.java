package com.austin.finance_app_idea;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/")
    public String Hello() {
        return "Finance App is running...";
    }
}
