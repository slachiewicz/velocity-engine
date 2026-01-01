package org.apache.velocity.test;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;
import org.apache.velocity.test.misc.TestLogger;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Tests concurrent template initialization with macros and #parse directives
 * to ensure the double-checked locking fix prevents deadlocks.
 *
 * This test reproduces the scenario described in the issue where:
 * - Multiple threads simultaneously process templates
 * - Templates define runtime macros
 * - Templates use #parse directives
 */
public class ConcurrentMacroInitializationTestCase extends BaseTestCase
{
    public ConcurrentMacroInitializationTestCase(String name)
    {
        super(name);
    }

    @Override
    public void setUp() throws Exception
    {
        super.setUp();
    }

    /**
     * Test concurrent initialization of templates with macros and parse directives.
     * This test should complete without deadlocking.
     */
    public void testConcurrentMacroInitialization() throws Exception
    {
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        ve.setProperty("resource.loader.string.class", "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        ve.setProperty(RuntimeConstants.RUNTIME_LOG_INSTANCE, new TestLogger());
        ve.init();

        // Create templates with macros and parse directives
        String headerTemplate = "#macro(msg $key)Message: $key#end\n" +
                                "Header content\n";

        String mainTemplate = "#macro(i18n $key)$key#end\n" +
                              "#parse('header.vm')\n" +
                              "#msg('hello')\n" +
                              "#i18n('world')\n";

        StringResourceRepository repo =
            StringResourceLoader.getRepository();
        repo.putStringResource("header.vm", headerTemplate);
        repo.putStringResource("main.vm", mainTemplate);

        // Number of concurrent threads
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<String>> futures = new ArrayList<>();

        // Submit concurrent tasks that will initialize templates simultaneously
        for (int i = 0; i < threadCount; i++)
        {
            final int threadId = i;
            Future<String> future = executor.submit(new Callable<String>()
            {
                @Override
                public String call() throws Exception
                {
                    VelocityContext context = new VelocityContext();
                    context.put("threadId", threadId);

                    StringWriter writer = new StringWriter();
                    Template template = ve.getTemplate("main.vm");
                    template.merge(context, writer);

                    return writer.toString();
                }
            });
            futures.add(future);
        }

        // Wait for all tasks to complete with a timeout
        // If there's a deadlock, this will timeout
        executor.shutdown();
        boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue("Concurrent template initialization timed out - possible deadlock", completed);

        // Verify all tasks completed successfully
        for (Future<String> future : futures)
        {
            String result = future.get();
            assertNotNull("Template rendering result should not be null", result);
            assertTrue("Result should contain header content", result.contains("Header content"));
            assertTrue("Result should contain macro output", result.contains("Message: hello"));
        }
    }

    /**
     * Test rapid concurrent template access with nested parse directives.
     */
    public void testNestedParseDirectivesConcurrency() throws Exception
    {
        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "string");
        ve.setProperty("resource.loader.string.class", "org.apache.velocity.runtime.resource.loader.StringResourceLoader");
        ve.setProperty(RuntimeConstants.RUNTIME_LOG_INSTANCE, new TestLogger());
        ve.init();

        StringResourceRepository repo = StringResourceLoader.getRepository();

        // Create a chain of templates that parse each other
        repo.putStringResource("template1.vm", "#macro(m1 $x)T1:$x#end\n#parse('template2.vm')");
        repo.putStringResource("template2.vm", "#macro(m2 $x)T2:$x#end\n#parse('template3.vm')");
        repo.putStringResource("template3.vm", "#macro(m3 $x)T3:$x#end\nEnd");

        int iterations = 20;
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(iterations);

        for (int i = 0; i < iterations; i++)
        {
            executor.submit(() -> {
                try
                {
                    // Wait for all threads to be ready
                    startLatch.await();

                    VelocityContext context = new VelocityContext();
                    StringWriter writer = new StringWriter();
                    Template template = ve.getTemplate("template1.vm");
                    template.merge(context, writer);

                    doneLatch.countDown();
                }
                catch (Exception e)
                {
                    fail("Exception in concurrent template rendering: " + e.getMessage());
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for completion with timeout
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue("Nested parse directives concurrent test timed out - possible deadlock", completed);
    }
}

