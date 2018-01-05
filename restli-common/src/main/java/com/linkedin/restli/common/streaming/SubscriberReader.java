/*
 * Copyright (c) 2017 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linkedin.restli.common.streaming;


import com.linkedin.data.ByteString;
import com.linkedin.java.util.concurrent.Flow.Subscriber;
import com.linkedin.java.util.concurrent.Flow.Subscription;
import com.linkedin.r2.message.stream.entitystream.ReadHandle;
import com.linkedin.r2.message.stream.entitystream.Reader;


/**
 * A {@link Reader} that bases on a {@link Subscriber}
 */
class SubscriberReader implements Reader
{
  private final Subscriber<? super ByteString> _subscriber;

  SubscriberReader(Subscriber<? super ByteString> subscriber)
  {
    _subscriber = subscriber;
  }

  @Override
  public void onInit(ReadHandle readHandle)
  {
    _subscriber.onSubscribe(new Subscription()
    {
      @Override
      public void request(long chunkNum)
      {
        if (chunkNum <= 0)
        {
          throw new IllegalArgumentException("Cannot request non-positive number of data chunks: " + chunkNum);
        }

        while (chunkNum > Integer.MAX_VALUE)
        {
          readHandle.request(Integer.MAX_VALUE);
          chunkNum -= Integer.MAX_VALUE;
        }
        readHandle.request((int) chunkNum);
      }

      @Override
      public void cancel()
      {
        readHandle.cancel();
      }
    });
  }

  @Override
  public void onDataAvailable(ByteString data)
  {
    _subscriber.onNext(data);
  }

  @Override
  public void onDone()
  {
    _subscriber.onComplete();
  }

  @Override
  public void onError(Throwable e)
  {
    _subscriber.onError(e);
  }
}
