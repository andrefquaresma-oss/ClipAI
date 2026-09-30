package com.clipai.application.candidate;

import java.util.Optional;
import java.util.UUID;

public interface CandidateReviewRepository {
    Optional<CandidateReview> findByCandidateId(UUID candidateId);

    CandidateReview save(CandidateReview review);

    static CandidateReviewRepository none() {
        return new CandidateReviewRepository() {
            @Override
            public Optional<CandidateReview> findByCandidateId(UUID candidateId) {
                return Optional.empty();
            }

            @Override
            public CandidateReview save(CandidateReview review) {
                return review;
            }
        };
    }
}
