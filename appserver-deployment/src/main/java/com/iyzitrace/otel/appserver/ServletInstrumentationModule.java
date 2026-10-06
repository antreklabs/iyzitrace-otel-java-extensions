package com.iyzitrace.otel.appserver;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import com.iyzitrace.otel.appserver.shared.WebAppTagger;

/**
 * Tags the HTTP SERVER span when a request enters a web application: on {@code Servlet#service}
 * and {@code Filter#doFilter} (requests served by a filter alone, e.g. Struts 2 or Wicket, never
 * reach a servlet), for both {@code jakarta.servlet} and {@code javax.servlet}. Requires Servlet
 * 3.0+ ({@code ServletRequest#getServletContext}). Enabled flag:
 * {@code otel.instrumentation.appserver-deployment.enabled}.
 */
public final class ServletInstrumentationModule extends InstrumentationModule {

  public ServletInstrumentationModule() {
    super("appserver-deployment");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("jakarta.servlet.Servlet").or(hasClassesNamed("javax.servlet.Servlet"));
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return Arrays.asList(
        new EntryPointInstrumentation("jakarta.servlet", "Servlet", "service", 2, "$JakartaAdvice"),
        new EntryPointInstrumentation("jakarta.servlet", "Filter", "doFilter", 3, "$JakartaAdvice"),
        new EntryPointInstrumentation("javax.servlet", "Servlet", "service", 2, "$JavaxAdvice"),
        new EntryPointInstrumentation("javax.servlet", "Filter", "doFilter", 3, "$JavaxAdvice"));
  }

  @Override
  public boolean isHelperClass(String className) {
    return className.startsWith("com.iyzitrace.otel.appserver.shared.");
  }

  // This module is built with Maven, so there is no muzzle code generation listing helpers for us.
  // Every class in the shared package (nested ones too) must be listed; a missing one fails the
  // advice silently. HelperClassNamesTest guards this.
  @Override
  public List<String> getAdditionalHelperClassNames() {
    return Arrays.asList(
        "com.iyzitrace.otel.appserver.shared.DeclaredService",
        "com.iyzitrace.otel.appserver.shared.DeploymentAttributes",
        "com.iyzitrace.otel.appserver.shared.DeploymentInfo",
        "com.iyzitrace.otel.appserver.shared.JBossModulesResolver",
        "com.iyzitrace.otel.appserver.shared.JBossModulesResolver$Entry",
        "com.iyzitrace.otel.appserver.shared.VendorResolver",
        "com.iyzitrace.otel.appserver.shared.VendorResolvers",
        "com.iyzitrace.otel.appserver.shared.WebAppTagger");
  }

  /** {@code <package>.<type>#<method>(<package>.ServletRequest, <package>.ServletResponse, ...)}. */
  static final class EntryPointInstrumentation implements TypeInstrumentation {

    private final String servletPackage;
    private final String type;
    private final String method;
    private final int arguments;
    private final String advice;

    EntryPointInstrumentation(
        String servletPackage, String type, String method, int arguments, String advice) {
      this.servletPackage = servletPackage;
      this.type = type;
      this.method = method;
      this.arguments = arguments;
      this.advice = advice;
    }

    @Override
    public ElementMatcher<ClassLoader> classLoaderOptimization() {
      return hasClassesNamed(servletPackage + "." + type);
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return implementsInterface(named(servletPackage + "." + type));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      ElementMatcher<MethodDescription> matcher =
          named(method)
              .and(isPublic())
              .and(takesArguments(arguments))
              .and(takesArgument(0, named(servletPackage + ".ServletRequest")))
              .and(takesArgument(1, named(servletPackage + ".ServletResponse")));
      transformer.applyAdviceToMethod(matcher, ServletInstrumentationModule.class.getName() + advice);
    }
  }

  @SuppressWarnings("unused")
  public static final class JakartaAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) jakarta.servlet.ServletRequest request) {
      jakarta.servlet.ServletContext context = request.getServletContext();
      if (context == null) {
        return;
      }
      if (!WebAppTagger.isKnown(context)) {
        WebAppTagger.register(
            context, context.getContextPath(), context.getResourceAsStream(WebAppTagger.CONFIG_PATH));
      }
      WebAppTagger.tag(context);
    }

    private JakartaAdvice() {}
  }

  @SuppressWarnings("unused")
  public static final class JavaxAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) javax.servlet.ServletRequest request) {
      javax.servlet.ServletContext context = request.getServletContext();
      if (context == null) {
        return;
      }
      if (!WebAppTagger.isKnown(context)) {
        WebAppTagger.register(
            context, context.getContextPath(), context.getResourceAsStream(WebAppTagger.CONFIG_PATH));
      }
      WebAppTagger.tag(context);
    }

    private JavaxAdvice() {}
  }
}
