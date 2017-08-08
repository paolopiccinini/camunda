/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.test.util.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * Throws an {@link IOException} on every {@code failureFrequency} call to {@link #read}.
 * Otherwise reads the next byte from the {@code underlyingInputStream}.
 */
public class AlwaysFailingInputStream extends InputStream
{
    @Override
    public int read() throws IOException
    {
        throw new IOException("Read failure - try again");
    }
}
