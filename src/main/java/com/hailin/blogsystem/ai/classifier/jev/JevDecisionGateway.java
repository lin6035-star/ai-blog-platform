package com.hailin.blogsystem.ai.classifier.jev;

import com.hailin.blogsystem.entity.dto.PageContextDTO;

@FunctionalInterface
public interface JevDecisionGateway {

    JevDecisionResult decide(String message, PageContextDTO pageContext);
}
