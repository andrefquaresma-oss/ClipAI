package com.clipai.api.candidate;

import com.clipai.domain.candidate.FootballEventType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/event-types")
public class FootballEventTypeController {
    @GetMapping
    public List<EventTypeResponse> list() {
        return Arrays.stream(FootballEventType.values())
                .map(type -> new EventTypeResponse(type.name(), type.name().replace('_', ' ')))
                .toList();
    }

    public record EventTypeResponse(String code, String label) {
    }
}
