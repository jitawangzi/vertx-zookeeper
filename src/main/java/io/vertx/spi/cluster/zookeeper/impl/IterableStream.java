/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.spi.cluster.zookeeper.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.shareddata.AsyncMapStream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author Thomas Segismont
 */
public class IterableStream<IN, OUT> implements AsyncMapStream<OUT> {

  private static final int BATCH_SIZE = 10;

  private final Context context;
  private final Supplier<Iterable<IN>> iterableSupplier;
  private final Function<IN, OUT> converter;

  private Iterable<IN> iterable;
  private Iterator<IN> iterator;
  private Deque<IN> queue;
  private Handler<OUT> dataHandler;
  private Handler<Throwable> exceptionHandler;
  private Handler<Void> endHandler;
  private boolean paused;
  private boolean readInProgress;
  private boolean closed;

  public IterableStream(Context context, Supplier<Iterable<IN>> iterableSupplier, Function<IN, OUT> converter) {
    this.context = context;
    this.iterableSupplier = iterableSupplier;
    this.converter = converter;
  }

  @Override
  public synchronized IterableStream<IN, OUT> exceptionHandler(Handler<Throwable> handler) {
    checkClosed();
    this.exceptionHandler = handler;
    return this;
  }

  private void checkClosed() {
    if (closed) {
      throw new IllegalArgumentException("Stream is closed");
    }
  }

  @Override
  public synchronized IterableStream<IN, OUT> handler(Handler<OUT> handler) {
    checkClosed();
    this.dataHandler = handler;
    context.<Iterable<IN>>executeBlocking(fut -> fut.complete(iterableSupplier.get()), ar -> {
      synchronized (this) {
        if (ar.succeeded()) {
          iterable = ar.result();
          if (dataHandler != null && !paused && !closed) {
            doRead();
          }
        } else if (exceptionHandler != null) {
          exceptionHandler.handle(ar.cause());
        }
      }
    });
    return this;
  }

  @Override
  public synchronized IterableStream<IN, OUT> pause() {
    checkClosed();
    paused = true;
    return this;
  }

  @Override
  public synchronized IterableStream<IN, OUT> resume() {
    checkClosed();
    if (paused) {
      paused = false;
      if (dataHandler != null) {
        doRead();
      }
    }
    return this;
  }

  private synchronized void doRead() {
    if (readInProgress) {
      return;
    }
    readInProgress = true;
    if (iterator == null) {
      iterator = iterable.iterator();
    }
    if (queue == null) {
      queue = new ArrayDeque<>(BATCH_SIZE);
    }
    if (!queue.isEmpty()) {
      context.runOnContext(v -> emitQueued());
      return;
    }
    for (int i = 0; i < BATCH_SIZE && iterator.hasNext(); i++) {
      queue.add(iterator.next());
    }
    if (queue.isEmpty()) {
      context.runOnContext(v -> {
        synchronized (this) {
          readInProgress = false;
          if (endHandler != null) {
            endHandler.handle(null);
          }
        }
      });
      return;
    }
    context.runOnContext(v -> emitQueued());
  }

  private synchronized void emitQueued() {
    while (!queue.isEmpty() && dataHandler != null && !paused && !closed) {
      dataHandler.handle(converter.apply(queue.remove()));
    }
    readInProgress = false;
    if (dataHandler != null && !paused && !closed) {
      doRead();
    }
  }

  @Override
  public synchronized IterableStream<IN, OUT> endHandler(Handler<Void> handler) {
    endHandler = handler;
    return this;
  }

  @Override
  public void close(Handler<AsyncResult<Void>> completionHandler) {
    context.runOnContext(v -> {
      synchronized (this) {
        closed = true;
        if (completionHandler != null) {
          completionHandler.handle(Future.succeededFuture());
        }
      }
    });
  }
}
