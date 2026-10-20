package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.api.delivery.catalogue.release.ReleaseController;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.ReleaseSort;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class StrictQueryParameterInterceptorTest {

    private final StrictQueryParameterInterceptor interceptor =
            new StrictQueryParameterInterceptor();
    private final HandlerMethod releaseHandler = releaseHandler();

    @Test
    void derivesAllowedParametersFromTheResolvedHandlerRegardlessOfRequestUri() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/routed-prefix/releases");
        request.addParameter("from", "2026-08-01");
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
        request.addParameter("from", "2026-08-01");
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

    @Test
    void allowsRepeatedValuesForMultiSelectArrayParameters() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/routed-prefix/releases");
        request.addParameter("from", "2026-08-01");
        request.addParameter("platformIds", "platform-a", "platform-b");
        request.addParameter("regionIds", "region-a", "region-b");

        assertThatCode(
                        () ->
                                interceptor.preHandle(
                                        request, new MockHttpServletResponse(), releaseHandler))
                .doesNotThrowAnyException();
    }

    @Test
    void stillRejectsRepeatedScalarParameters() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/routed-prefix/releases");
        request.addParameter("from", "2026-08-01", "2026-08-02");

        assertThatThrownBy(
                        () ->
                                interceptor.preHandle(
                                        request, new MockHttpServletResponse(), releaseHandler))
                .isInstanceOf(ApiRequestException.class)
                .satisfies(
                        exception ->
                                org.assertj.core.api.Assertions.assertThat(
                                                ((ApiRequestException) exception).code())
                                        .isEqualTo(ProblemCode.FILTER_INVALID));
    }

    @Test
    void acceptsOnlyCanonicalBooleanLiteralsForBooleanParameters() {
        for (String accepted : new String[] {"true", "false"}) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/routed-prefix/releases");
            request.addParameter("from", "2026-08-01");
            request.addParameter("includeApproximateDates", accepted);

            assertThatCode(
                            () ->
                                    interceptor.preHandle(
                                            request, new MockHttpServletResponse(), releaseHandler))
                    .doesNotThrowAnyException();
        }
        for (String rejected : new String[] {"yes", "1", "TRUE", ""}) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/routed-prefix/releases");
            request.addParameter("from", "2026-08-01");
            request.addParameter("includeApproximateDates", rejected);

            assertThatThrownBy(
                            () ->
                                    interceptor.preHandle(
                                            request, new MockHttpServletResponse(), releaseHandler))
                    .isInstanceOf(ApiRequestException.class)
                    .satisfies(
                            exception -> {
                                ApiRequestException problem = (ApiRequestException) exception;
                                org.assertj.core.api.Assertions.assertThat(problem.code())
                                        .isEqualTo(ProblemCode.FILTER_INVALID);
                                org.assertj.core.api.Assertions.assertThat(problem.pointer())
                                        .isEqualTo("/query/includeApproximateDates");
                            });
        }
    }

    @Test
    void reportsAnUnsupportedOrderAsASortProblem() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/routed-prefix/releases");
        request.addParameter("sort", "popularity");

        assertThatThrownBy(
                        () ->
                                interceptor.preHandle(
                                        request, new MockHttpServletResponse(), releaseHandler))
                .isInstanceOf(ApiRequestException.class)
                .satisfies(
                        exception -> {
                            ApiRequestException problem = (ApiRequestException) exception;
                            org.assertj.core.api.Assertions.assertThat(problem.code())
                                    .isEqualTo(ProblemCode.SORT_INVALID);
                            org.assertj.core.api.Assertions.assertThat(problem.pointer())
                                    .isEqualTo("/query/sort");
                        });
    }

    @Test
    void rejectsTheRetiredViewAndWeeksParameters() {
        for (String retired : new String[] {"view", "weeks"}) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/routed-prefix/releases");
            request.addParameter(retired, "1");

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
    }

    private static HandlerMethod releaseHandler() {
        try {
            ReleaseController controller =
                    new ReleaseController(null, null, null, null, null, null);
            return new HandlerMethod(
                    controller,
                    ReleaseController.class.getMethod(
                            "listReleases",
                            LocalDate.class,
                            LocalDate.class,
                            ReleaseSort.class,
                            Boolean.class,
                            Set.class,
                            Set.class,
                            Integer.class,
                            Integer.class,
                            String.class));
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }
}
