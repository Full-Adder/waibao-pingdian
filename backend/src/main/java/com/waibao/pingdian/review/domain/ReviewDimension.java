package com.waibao.pingdian.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "review_dimension")
public class ReviewDimension {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension_code", nullable = false, length = 32)
    private DimensionCode dimensionCode;

    @Column(name = "score", nullable = false)
    private int score;

    public UUID getId() {
        return id;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public void setReviewId(UUID reviewId) {
        this.reviewId = reviewId;
    }

    public DimensionCode getDimensionCode() {
        return dimensionCode;
    }

    public void setDimensionCode(DimensionCode dimensionCode) {
        this.dimensionCode = dimensionCode;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }
}
