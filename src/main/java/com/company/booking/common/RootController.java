package com.company.booking.common;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** People open the API port in a browser first: send them somewhere useful instead of a 401 JSON blob. */
@Controller
@Hidden
public class RootController {

    @GetMapping("/")
    public String root() {
        return "redirect:/swagger-ui.html";
    }
}
