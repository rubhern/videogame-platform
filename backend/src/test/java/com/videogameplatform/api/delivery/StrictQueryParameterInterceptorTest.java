package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.ReleaseView;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class StrictQueryParameterInterceptorTest {

    private final StrictQueryParameterInterceptor interceptor =
            new StrictQueryParameterInterceptor(mock(ReleaseApiMetrics.class));
    private final HandlerMethod releaseHandler = releaseHandler();

    @Test
    void derivesAllowedParametersFromTheResolvedHandlerRegardlessOfRequestUri() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/routed-prefix/releases");
        request.addParameter("view", "recent");
        request.addParameter("page", "1");

        assertThatCode(
                        () ->
                                interceptor.preHandle(
                                        request, new MockHttpServletResponse(), releaseHandler))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownParametersForTheResolvedHandlerRegardlessOfRequestUri() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/routed-prefix/releases");
        request.addParameter("view", "recent");
        request.addParameter("unexpected", "true");

        assertThatThrownBy(
                        () ->
                                interceptor.preHandle(
                                        request, new MockHttpServletResponse(), releaseHandler))
                .isInstanceOf(ApiRequestException.class)
                .satisfies(
                        exception ->
                                org.assertj.core.api.Assertions.assertThat(
                                                ((ApiRequestException) exception).code())
                                        .isEqualTo(ProblemCode.REQUEST_PARAMETER_UNKNOWN));
    }

    private static HandlerMethod releaseHandler() {
        try {
            ReleaseController controller = new ReleaseController(null, null, null, null, null);
            return new HandlerMethod(
                    controller,
                    ReleaseController.class.getMethod(
                            "listReleases",
                            ReleaseView.class,
                            String.class,
                            String.class,
                            Integer.class,
                            Integer.class,
                            String.class));
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }
}
