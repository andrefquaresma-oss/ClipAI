package com.clipai.application.ports;

import java.net.URI;

public interface MediaSource {
    String name();

    boolean supports(URI sourceUrl);
}
