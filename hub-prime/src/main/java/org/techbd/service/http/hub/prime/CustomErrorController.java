package org.techbd.service.http.hub.prime;

import java.util.Map;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Controller
public class CustomErrorController implements ErrorController {

    private final ErrorAttributes errorAttributes;

    public CustomErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping("/error")
    public String getErrorPage(final Model model, final HttpServletRequest request, HttpServletResponse response) {
        ServletWebRequest webRequest = new ServletWebRequest(request);

        Map<String, Object> errorDetails = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());

        System.out.println("Error details: " + errorDetails);


        // Get error details, including the custom message from sendError
        String status = String.valueOf(response.getStatus());
        String error = (String) errorDetails.get("error");
        String path = (String) errorDetails.get("path");
        String errorMessage = String.format("Error %s: %s at path %s",
                status, error, path);

        // model.addAttribute("status", status);
        // model.addAttribute("error", errorDetails.get("error"));
        // model.addAttribute("path", path != null ? path : "Unknown path");
        model.addAttribute("message", errorMessage);

        return "page/error";
    }

    public String getErrorPath() {
        return "/error";
    }
}