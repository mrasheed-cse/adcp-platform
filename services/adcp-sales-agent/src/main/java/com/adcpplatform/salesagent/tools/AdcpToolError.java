package com.adcpplatform.salesagent.tools;

/**
 * Thrown out of a @Tool method so the MCP response is marked isError=true.
 * The message is the AdCP {"errors":[...]} JSON, which becomes the error's text content,
 * so the buyer agent still gets machine-readable codes and fields.
 *
 * Requires spring.ai.tools.throw-exception-on-error=true (set in application.yml); without it
 * Spring AI returns the message as a normal, non-error result.
 */
public class AdcpToolError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AdcpToolError(String errorsJson, Throwable cause) {
        super(errorsJson, cause);
    }
}
