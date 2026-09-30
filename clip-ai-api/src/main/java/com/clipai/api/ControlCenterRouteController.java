package com.clipai.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@Controller
public class ControlCenterRouteController {
    @GetMapping("/assets")
    public String assets() {
        return "redirect:/#/assets";
    }

    @GetMapping("/assets/{mediaAssetId}")
    public String asset(@PathVariable UUID mediaAssetId) {
        return "redirect:/#/assets/" + mediaAssetId;
    }

    @GetMapping("/assets/{mediaAssetId}/candidates/{candidateId}")
    public String candidate(@PathVariable UUID mediaAssetId, @PathVariable UUID candidateId) {
        return "redirect:/#/assets/" + mediaAssetId + "/candidates/" + candidateId;
    }

    @GetMapping({"/ground-truth", "/ground-truth/{mediaAssetId}"})
    public String groundTruth(@PathVariable(required = false) UUID mediaAssetId) {
        return mediaAssetId == null
                ? "redirect:/#/ground-truth"
                : "redirect:/#/ground-truth/" + mediaAssetId;
    }
}
