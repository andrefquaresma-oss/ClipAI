package com.clipai.api.error;

import com.clipai.application.media.MediaAssetNotFoundException;
import com.clipai.application.media.InvalidVideoUploadException;
import com.clipai.application.media.UploadTooLargeException;
import com.clipai.application.candidate.CandidateDetectionConflictException;
import com.clipai.application.candidate.CandidateDetectionSchedulingException;
import com.clipai.application.candidate.CandidateClipExportConflictException;
import com.clipai.application.candidate.CandidateClipBatchNotFoundException;
import com.clipai.application.candidate.CandidateClipBatchSchedulingException;
import com.clipai.application.candidate.CandidateClipNotFoundException;
import com.clipai.application.candidate.CandidateEventNotFoundException;
import com.clipai.application.candidate.DetectionRunNotFoundException;
import com.clipai.application.candidate.GoalReviewAssetNotFoundException;
import com.clipai.application.candidate.GoalReviewAssetUnsupportedException;
import com.clipai.application.scoreboard.ScoreboardAnalysisNotFoundException;
import com.clipai.application.transcript.TranscriptNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MediaAssetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse notFound(MediaAssetNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(TranscriptNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse transcriptNotFound(TranscriptNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ScoreboardAnalysisNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse scoreboardAnalysisNotFound(ScoreboardAnalysisNotFoundException exception,
                                                HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException exception,
                                                    HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        String reason = exception.getReason() == null ? status.toString() : exception.getReason();
        return ResponseEntity.status(status).body(new ApiErrorResponse(Instant.now(), status.value(),
                status.toString(), reason, request.getRequestURI()));
    }

    @ExceptionHandler(CandidateEventNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse candidateEventNotFound(CandidateEventNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(DetectionRunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse detectionRunNotFound(DetectionRunNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CandidateClipNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse candidateClipNotFound(CandidateClipNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(CandidateClipBatchNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse candidateClipBatchNotFound(CandidateClipBatchNotFoundException exception,
                                                HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(GoalReviewAssetNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiErrorResponse goalReviewAssetNotFound(GoalReviewAssetNotFoundException exception,
                                             HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(GoalReviewAssetUnsupportedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiErrorResponse goalReviewAssetUnsupported(GoalReviewAssetUnsupportedException exception,
                                                HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), request);
    }

    @ExceptionHandler(CandidateClipExportConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse candidateClipExportConflict(CandidateClipExportConflictException exception,
                                                  HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(CandidateDetectionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse candidateDetectionConflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Candidate detection is not ready or is already processing", request);
    }

    @ExceptionHandler(CandidateDetectionSchedulingException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiErrorResponse candidateDetectionUnavailable(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Candidate detection could not be scheduled", request);
    }

    @ExceptionHandler(CandidateClipBatchSchedulingException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiErrorResponse candidateClipBatchUnavailable(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Candidate clip generation could not be scheduled", request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class, InvalidVideoUploadException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse badRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Request is invalid", request);
    }

    @ExceptionHandler({UploadTooLargeException.class, MaxUploadSizeExceededException.class})
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ApiErrorResponse tooLarge(HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the configured maximum size", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse conflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Resource conflicts with existing data", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiErrorResponse stateConflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "The requested operation conflicts with the current state", request);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiErrorResponse unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure path={}", request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ApiErrorResponse error(HttpStatus status, String message, HttpServletRequest request) {
        return new ApiErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message,
                request.getRequestURI());
    }
}
