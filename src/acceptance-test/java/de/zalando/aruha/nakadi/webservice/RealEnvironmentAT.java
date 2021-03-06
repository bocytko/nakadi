package de.zalando.aruha.nakadi.webservice;

import com.jayway.restassured.RestAssured;
import com.jayway.restassured.specification.RequestSpecification;

import java.util.Optional;

import static com.jayway.restassured.RestAssured.given;
import static java.util.Optional.ofNullable;

public abstract class RealEnvironmentAT extends BaseAT {

    protected final Optional<String> OAUTH_TOKEN;

    public RealEnvironmentAT() {
        OAUTH_TOKEN = ofNullable(System.getenv("NAKADI_OAUTH_TOKEN"));

        RestAssured.baseURI = ofNullable(System.getenv("NAKADI_BASE_URL"))
                .orElse(RestAssured.DEFAULT_URI);

        RestAssured.port = Integer.parseInt(ofNullable(System.getenv("NAKADI_PORT"))
                .orElse(Integer.toString(RestAssured.DEFAULT_PORT)));
    }

    protected RequestSpecification requestSpec() {
        final RequestSpecification requestSpec = given();
        OAUTH_TOKEN.ifPresent(token -> requestSpec.header("Authorization", "Bearer " + token));
        return requestSpec;
    }

}
