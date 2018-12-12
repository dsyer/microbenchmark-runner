/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jmh.mbr.core;

import java.util.Collection;

import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.format.OutputFormat;

/**
 * @author Dave Syer
 *
 */
public class TestResultsWriterFactory implements ResultsWriterFactory {

	@Override
	public ResultsWriter forUri(String uri) {
		return uri.equals("urn:empty") ? new TestResultsWriter() : null;
	}

	class TestResultsWriter implements ResultsWriter {
		@Override
		public void write(OutputFormat output, Collection<RunResult> results) {
		}
	}
}
