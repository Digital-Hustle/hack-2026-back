package ru.zuzex.practice.gatewayserver.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class GatewayProblems {

    private final ObjectMapper mapper;

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().setCacheControl("no-store");
        var problem = ProblemDetail.forStatus(status);
        problem.setTitle(HttpStatus.valueOf(status.value()).getReasonPhrase());
        try {
            byte[] bytes = mapper.writeValueAsBytes(problem);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }
}
