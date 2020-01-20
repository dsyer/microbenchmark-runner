/*
 * Copyright 2018 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * http://www.eclipse.org/legal/epl-v20.html
 */
package jmh.mbr.junit4;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jmh.mbr.core.Environment;
import jmh.mbr.core.BenchmarkConfiguration;
import jmh.mbr.core.JmhSupport;
import jmh.mbr.core.StringUtils;
import jmh.mbr.core.model.BenchmarkClass;
import jmh.mbr.core.model.BenchmarkDescriptor;
import jmh.mbr.core.model.BenchmarkDescriptorFactory;
import jmh.mbr.core.model.BenchmarkFixture;
import jmh.mbr.core.model.BenchmarkMethod;
import jmh.mbr.core.model.BenchmarkResults;
import jmh.mbr.core.model.BenchmarkResults.MetaData;
import jmh.mbr.core.model.HierarchicalBenchmarkDescriptor;
import org.junit.runner.Description;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runner.manipulation.Sortable;
import org.junit.runner.manipulation.Sorter;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Defaults;
import org.openjdk.jmh.runner.NoBenchmarksException;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.format.OutputFormat;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.util.UnCloseablePrintStream;
import org.openjdk.jmh.util.Utils;

/**
 * JMH Microbenchmark runner that turns methods annotated with {@link Benchmark} into runnable methods allowing
 * execution through JUnit.
 */
public class Microbenchmark extends ParentRunner<BenchmarkDescriptor> implements Filterable, Sortable {

	private final List<? extends BenchmarkDescriptor> children;
	private final Map<BenchmarkDescriptor, Description> descriptions = new ConcurrentHashMap<>();
	private final Map<String, List<BenchmarkDescriptor>> parametrizedDescriptions = new LinkedHashMap<>();
	private final Map<String, Description> fixtureMethodDescriptions = new LinkedHashMap<>();

	private final Object childrenLock = new Object();
	private final JmhSupport jmhRunner = new JmhSupport(BenchmarkConfiguration.defaultOptions());
	private final BenchmarkClass benchmarkClass;

	private Collection<BenchmarkDescriptor> filteredChildren;

	/**
	 * Creates a {@link Microbenchmark} to run {@link Class test class}.
	 *
	 * @param testClass
	 * @throws InitializationError if the test class is malformed.
	 */
	public Microbenchmark(Class<?> testClass) throws InitializationError {

		super(testClass);
		this.benchmarkClass = BenchmarkDescriptorFactory.create(testClass).createDescriptor();
		this.children = benchmarkClass.getChildren();

		for (BenchmarkDescriptor child : children) {

			if (child instanceof HierarchicalBenchmarkDescriptor) {

				HierarchicalBenchmarkDescriptor descriptor = (HierarchicalBenchmarkDescriptor) child;
				for (BenchmarkDescriptor nested : descriptor.getChildren()) {

					BenchmarkFixture fixture = (BenchmarkFixture) nested;

					parametrizedDescriptions.computeIfAbsent(fixture.getDisplayName(), it -> new ArrayList<>()).add(descriptor);
				}
			}
		}
	}

	/**
	 * Ignore JUnit validation as we're using JMH here.
	 *
	 * @param errors
	 */
	@Override
	protected void collectInitializationErrors(List<Throwable> errors) {
	}

	@Override
	protected List<BenchmarkDescriptor> getChildren() {
		return (List) children;
	}

	@Override
	protected Statement classBlock(RunNotifier notifier) {
		return childrenInvoker(notifier);
	}

	@Override
	public Description getDescription() {
		Description description = describeChild(benchmarkClass);
		return description;
	}

	@Override
	protected Description describeChild(BenchmarkDescriptor child) {

		Description description = descriptions.get(child);
		if (description == null) {

			description = createDescription(child);
			descriptions.put(child, description);
		}

		return description;
	}

	private Description createDescription(BenchmarkDescriptor child) {

		if (child instanceof BenchmarkClass) {

			BenchmarkClass benchmarkClass = (BenchmarkClass) child;
			Description description = Description.createSuiteDescription(getName(), UUID.randomUUID(),
					getRunnerAnnotations());

			for (BenchmarkDescriptor childDescriptor : benchmarkClass.getChildren()) {

				if (childDescriptor instanceof BenchmarkMethod) {
					description.addChild(describeChild(childDescriptor));
				}
			}

			for (Entry<String, List<BenchmarkDescriptor>> entry : parametrizedDescriptions.entrySet()) {

				Description fixture = Description.createSuiteDescription(entry.getKey());

				for (BenchmarkDescriptor nested : entry.getValue()) {

					BenchmarkMethod benchmarkMethod = getBenchmarkMethod(nested);
					Description nestedDescription = createDescription(benchmarkMethod);
					fixtureMethodDescriptions.put(entry.getKey() + "-" + nestedDescription.getMethodName(), nestedDescription);

					fixture.addChild(nestedDescription);
				}

				description.addChild(fixture);
			}

			return description;
		}

		if (child instanceof BenchmarkMethod) {

			BenchmarkMethod method = (BenchmarkMethod) child;
			return Description.createTestDescription(method.getDeclaringClass().getName(), method.getName());
		}

		if (child instanceof HierarchicalBenchmarkDescriptor) {

			HierarchicalBenchmarkDescriptor hierarchical = (HierarchicalBenchmarkDescriptor) child;
			Description description = null;
			if (hierarchical.getDescriptor() instanceof BenchmarkMethod) {

				BenchmarkMethod method = (BenchmarkMethod) hierarchical.getDescriptor();
				description = Description.createTestDescription(method.getDeclaringClass().getName(), method.getName());
			}

			for (BenchmarkDescriptor childDescriptor : hierarchical.getChildren()) {
				description.addChild(describeChild(childDescriptor));
			}

			return description;
		}

		if (child instanceof BenchmarkFixture) {
			return Description.createSuiteDescription(((BenchmarkFixture) child).getDisplayName());
		}

		throw new IllegalArgumentException("Cannot describe" + child);
	}

	@Override
	public void filter(Filter filter) throws NoTestsRemainException {

		synchronized (childrenLock) {

			List<BenchmarkDescriptor> children = new ArrayList<>(getFilteredChildren());
			List<BenchmarkDescriptor> filtered = children.stream().filter(it -> {

				if (filter.shouldRun(describeChild(it))) {
					try {
						filter.apply(it);
						return true;
					} catch (NoTestsRemainException e) {
						return false;
					}
				}
				return false;
			}).collect(Collectors.toList());

			if (filtered.isEmpty()) {
				throw new NoTestsRemainException();
			}

			filteredChildren = filtered;
		}
	}

	@Override
	public void sort(Sorter sorter) {

		synchronized (childrenLock) {

			getFilteredChildren().forEach(sorter::apply);

			List<BenchmarkDescriptor> sortedChildren = new ArrayList<>(getFilteredChildren());

			sortedChildren.sort((o1, o2) -> sorter.compare(describeChild(o1), describeChild(o2)));

			filteredChildren = sortedChildren;
		}
	}

	@Override
	protected void runChild(BenchmarkDescriptor child, RunNotifier notifier) {
	}

	/**
	 * Run matching {@link org.openjdk.jmh.annotations.Benchmark} methods.
	 */
	@Override
	protected Statement childrenInvoker(RunNotifier notifier) {

		Collection<BenchmarkDescriptor> methods = getFilteredChildren();
		CacheFunction cache = new CacheFunction(methods, this::describeChild, (method, fixture) -> {

			return fixtureMethodDescriptions.get(fixture.getDisplayName() + "-" + method.getName());
		});

		if (methods.isEmpty()) {
			return new Statement() {
				@Override
				public void evaluate() {
				}
			};
		}

		return new Statement() {

			@Override
			public void evaluate() throws Throwable {
				try {
					doRun(notifier, methods, cache);
				} catch (NoBenchmarksException | NoTestsRemainException e) {
					methods.forEach(it -> notifier.fireTestIgnored(describeChild(it)));
				}
			}
		};
	}

	void doRun(RunNotifier notifier, Collection<BenchmarkDescriptor> methods, CacheFunction cache) throws Exception {

		Class<?> jmhTestClass = getTestClass().getJavaClass();
		List<String> includes = includes(jmhTestClass, methods);

		if (includes.isEmpty()) {
			throw new NoTestsRemainException();
		}

		ChainedOptionsBuilder optionsBuilder = jmhRunner.options(jmhTestClass);

		if (!jmhRunner.isEnabled()) {
			notifier.fireTestIgnored(getDescription());
			return;
		}

		includes.forEach(optionsBuilder::include);

		Options options = optionsBuilder.build();
		NotifyingOutputFormat notifyingOutputFormat = new NotifyingOutputFormat(notifier, cache,
				createOutputFormat(options));

		jmhRunner.publishResults(notifyingOutputFormat, new BenchmarkResults(MetaData.from(Environment.jmhConfigProperties()), new Runner(options, notifyingOutputFormat).run()));
	}

	/**
	 * Get the regex for all benchmarks to be included in the run. By default every benchmark within classes matching the
	 * fqcn. <br />
	 * The {@literal benchmark} command line argument allows overriding the defaults using {@code #} as class / method
	 * name separator.
	 *
	 * @param testClass
	 * @param methods
	 * @return never {@literal null}.
	 */
	private List<String> includes(Class<?> testClass, Collection<BenchmarkDescriptor> methods) {

		String tests = Environment.getProperty("benchmark");

		if (!StringUtils.hasText(tests)) {

			return methods.stream().map(Microbenchmark::getBenchmarkMethod)
					.map(it -> Pattern.quote(it.getDeclaringClass().getName()) + "\\." + Pattern.quote(it.getName()) + "$")
					.collect(Collectors.toList());
		}

		if (tests.contains(testClass.getName()) || tests.contains(testClass.getSimpleName())) {
			if (!tests.contains("#")) {
				return Collections.singletonList(".*" + tests + ".*");
			}

			String[] args = tests.split("#");
			return Collections.singletonList(".*" + args[0] + "." + args[1]);
		}

		return Collections.emptyList();
	}

	private Collection<BenchmarkDescriptor> getFilteredChildren() {

		if (filteredChildren == null) {
			synchronized (childrenLock) {
				if (filteredChildren == null) {
					filteredChildren = getChildren();
				}
			}
		}
		return filteredChildren;
	}

	private static OutputFormat createOutputFormat(Options options) {

		// sadly required here as the check cannot be made before calling this method in
		// constructor
		if (options == null) {
			throw new IllegalArgumentException("Options not allowed to be null.");
		}

		PrintStream out;
		if (options.getOutput().hasValue()) {
			try {
				out = new PrintStream(options.getOutput().get());
			} catch (FileNotFoundException ex) {
				throw new IllegalStateException(ex);
			}
		} else {
			// Protect the System.out from accidental closing
			try {
				out = new UnCloseablePrintStream(System.out, Utils.guessConsoleEncoding());
			} catch (UnsupportedEncodingException ex) {
				throw new IllegalStateException(ex);
			}
		}

		return OutputFormatFactory.createFormatInstance(out, options.verbosity().orElse(Defaults.VERBOSITY));
	}

	private static String getBenchmarkName(BenchmarkDescriptor descriptor) {

		BenchmarkMethod benchmarkMethod = getBenchmarkMethod(descriptor);
		Method method = benchmarkMethod.getMethod();

		return method.getDeclaringClass().getName() + "." + method.getName();
	}

	private static BenchmarkMethod getBenchmarkMethod(BenchmarkDescriptor descriptor) {

		if (descriptor instanceof BenchmarkMethod) {
			return (BenchmarkMethod) descriptor;
		}

		if (descriptor instanceof HierarchicalBenchmarkDescriptor) {
			HierarchicalBenchmarkDescriptor hierarchical = (HierarchicalBenchmarkDescriptor) descriptor;
			if (hierarchical.getDescriptor() instanceof BenchmarkMethod) {
				return (BenchmarkMethod) hierarchical.getDescriptor();
			}
		}

		throw new IllegalStateException("Cannot obtain BenchmarkMethod from" + descriptor);
	}

	/**
	 * {@link OutputFormat} that delegates to another {@link OutputFormat} and notifies {@link RunNotifier} about the
	 * progress.
	 */
	static class NotifyingOutputFormat implements OutputFormat {

		private final RunNotifier notifier;
		private final CacheFunction descriptionResolver;
		private final OutputFormat delegate;
		private final List<String> log = new CopyOnWriteArrayList<>();

		private volatile BenchmarkParams lastKnownBenchmark;
		private volatile boolean recordOutput;

		NotifyingOutputFormat(RunNotifier notifier, CacheFunction methods, OutputFormat delegate) {
			this.notifier = notifier;
			this.descriptionResolver = methods;
			this.delegate = delegate;
		}

		@Override
		public void iteration(BenchmarkParams benchParams, IterationParams params, int iteration) {
			delegate.iteration(benchParams, params, iteration);
		}

		@Override
		public void iterationResult(BenchmarkParams benchParams, IterationParams params, int iteration,
				IterationResult data) {
			delegate.iterationResult(benchParams, params, iteration, data);
		}

		@Override
		public void startBenchmark(BenchmarkParams benchParams) {

			log.clear();

			lastKnownBenchmark = benchParams;

			notifier.fireTestStarted(descriptionResolver.apply(benchParams));

			delegate.startBenchmark(benchParams);
		}

		@Override
		public void endBenchmark(BenchmarkResult result) {

			recordOutput = false;
			BenchmarkParams lastKnownBenchmark = this.lastKnownBenchmark;
			if (result != null) {
				notifier.fireTestFinished(descriptionResolver.apply(result.getParams()));
			} else if (lastKnownBenchmark != null) {

				String output = StringUtils.collectionToDelimitedString(log, System.getProperty("line.separator"));
				notifier.fireTestFailure(
						new Failure(descriptionResolver.apply(lastKnownBenchmark), new JmhRunnerException(output)));
			}

			log.clear();
			delegate.endBenchmark(result);
		}

		@Override
		public void startRun() {
			delegate.startRun();
		}

		@Override
		public void endRun(Collection<RunResult> result) {
			delegate.endRun(result);
		}

		@Override
		public void print(String s) {
			delegate.print(s);
		}

		@Override
		public void println(String s) {

			if (recordOutput && StringUtils.hasText(s)) {
				log.add(s);
			}

			if (s.equals("<failure>")) {
				recordOutput = true;
			}

			delegate.println(s);
		}

		@Override
		public void flush() {
			delegate.flush();
		}

		@Override
		public void close() {
			delegate.close();
		}

		@Override
		public void verbosePrintln(String s) {
			delegate.verbosePrintln(s);
		}

		@Override
		public void write(int b) {
			delegate.write(b);
		}

		@Override
		public void write(byte[] b) throws IOException {
			delegate.write(b);
		}
	}

	/**
	 * Exception proxy without stack trace.
	 */
	static class JmhRunnerException extends RuntimeException {

		private static final long serialVersionUID = -1385006784559013618L;

		JmhRunnerException(String message) {
			super(message);
		}

		@Override
		public synchronized Throwable fillInStackTrace() {
			return null;
		}
	}

	/**
	 * Cache {@link Function} for benchmark names to {@link Description}.
	 */
	static class CacheFunction implements Function<BenchmarkParams, Description> {

		private final Map<String, BenchmarkDescriptor> methodMap = new ConcurrentHashMap<>();
		private final Collection<BenchmarkDescriptor> methods;
		private final Function<BenchmarkDescriptor, Description> describeFunction;
		private final BiFunction<BenchmarkMethod, BenchmarkFixture, Description> describeParametrizedMethodFunction;

		CacheFunction(Collection<BenchmarkDescriptor> methods, Function<BenchmarkDescriptor, Description> describeFunction,
				BiFunction<BenchmarkMethod, BenchmarkFixture, Description> describeParametrizedMethodFunction) {
			this.methods = methods;
			this.describeFunction = describeFunction;
			this.describeParametrizedMethodFunction = describeParametrizedMethodFunction;
		}

		/**
		 * Resolve a benchmark name (fqcn + "." + method name) to a {@link Description}.
		 *
		 * @param benchmarkName
		 * @return
		 */
		@Override
		public Description apply(BenchmarkParams benchmark) {

			BenchmarkDescriptor descriptor = getBenchmarkDescriptor(benchmark);

			if (descriptor instanceof HierarchicalBenchmarkDescriptor) {

				Map<String, String> lookup = new HashMap<>();
				for (String key : benchmark.getParamsKeys()) {
					lookup.put(key, benchmark.getParam(key));
				}

				for (BenchmarkDescriptor child : ((HierarchicalBenchmarkDescriptor) descriptor).getChildren()) {

					if (child instanceof BenchmarkFixture) {
						BenchmarkFixture fixture = (BenchmarkFixture) child;

						if (fixture.getFixture().equals(lookup)) {
							return describeParametrizedMethodFunction.apply(getBenchmarkMethod(descriptor), fixture);
						}
					}
				}
			}

			return describeFunction.apply(descriptor);
		}

		public Description resolveMethod(BenchmarkParams benchmark) {
			return describeFunction.apply(getBenchmarkDescriptor(benchmark));
		}

		public BenchmarkDescriptor getBenchmarkDescriptor(BenchmarkParams benchmark) {

			return methodMap.computeIfAbsent(benchmark.getBenchmark(), key -> {

				Optional<BenchmarkDescriptor> method = methods.stream().filter(it -> getBenchmarkName(it).equals(key))
						.findFirst();

				return method.orElseThrow(() -> new IllegalArgumentException(
						String.format("Cannot resolve %s to a BenchmarkDescriptor!", benchmark.getBenchmark())));
			});
		}
	}
}
