package com.clipai.application.groundtruth;

public record GroundTruthMetrics(boolean sufficientForEvaluation, int groundTruthCount,
                                 int detectedCount, int truePositive, int falsePositive,
                                 int falseNegative, String sufficiencyMessage) {
}
