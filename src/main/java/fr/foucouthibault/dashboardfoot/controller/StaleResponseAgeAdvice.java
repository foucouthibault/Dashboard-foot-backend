package fr.foucouthibault.dashboardfoot.controller;

import fr.foucouthibault.dashboardfoot.service.StaleResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Ajoute l'en-tête {@code Age} (RFC 9111, en secondes) à une réponse servie depuis la copie de
 * secours du service ({@link StaleResponse}). Une réponse fraîche n'en porte pas : le frontend
 * peut ainsi signaler une donnée ancienne sans que le corps, donc le contrat, ne change.
 */
@RestControllerAdvice(assignableTypes = CompetitionProxyController.class)
public class StaleResponseAgeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            StaleResponse.ageOf(servletRequest.getServletRequest())
                .ifPresent(age -> response.getHeaders().set(HttpHeaders.AGE, String.valueOf(age.toSeconds())));
        }
        return body;
    }
}
