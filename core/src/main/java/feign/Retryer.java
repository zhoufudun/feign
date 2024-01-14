/*
 * Copyright 2012-2023 The Feign Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package feign;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Cloned for each invocation to {@link Client#execute(Request, feign.Request.Options)}.
 * Implementations may keep state to determine if retry operations should continue or not.
 */
public interface Retryer extends Cloneable {

    /**
     * if retry is permitted, return (possibly after sleeping). Otherwise, propagate the exception.
     */
    void continueOrPropagate(RetryableException e);

    Retryer clone();

    /**
     * 默认的重试机制
     */
    class Default implements Retryer {

        private final int maxAttempts;
        private final long period;
        private final long maxPeriod;
        int attempt;
        long sleptForMillis;

        public Default() {
            this(100, SECONDS.toMillis(1), 5);
        }

        public Default(long period, long maxPeriod, int maxAttempts) {
            this.period = period;
            this.maxPeriod = maxPeriod;
            this.maxAttempts = maxAttempts;
            this.attempt = 1;
        }

        // visible for testing;
        protected long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        /**
         * 简要解释：
         * <p>
         * 如果已经达到最大重试次数 (maxAttempts)，则直接抛出异常。
         * 如果异常包含 retryAfter 信息，计算下次重试的时间间隔。
         * 如果计算出的时间间隔超过最大允许间隔 (maxPeriod)，则使用最大允许间隔。
         * 如果计算出的时间间隔小于 0，则不进行重试。
         * 如果异常不包含 retryAfter 信息，计算下次重试的时间间隔。
         * 线程休眠，等待下次重试。
         * 如果线程被中断，重新设置中断状态并抛出异常。
         * 记录已经休眠的总时间
         *
         * @param e
         */
        public void continueOrPropagate(RetryableException e) {
            // 如果已经达到最大重试次数，则直接抛出异常
            if (attempt++ >= maxAttempts) {
                throw e;
            }

            long interval;
            // 如果异常包含 retryAfter 信息，计算下次重试的时间间隔
            if (e.retryAfter() != null) {
                interval = e.retryAfter() - currentTimeMillis();
                // 如果计算出的时间间隔超过最大允许间隔，则使用最大允许间隔
                if (interval > maxPeriod) {
                    interval = maxPeriod;
                }
                // 如果计算出的时间间隔小于 0，则不进行重试
                if (interval < 0) {
                    return;
                }
            } else {
                // 否则，计算下次重试的时间间隔
                interval = nextMaxInterval();
            }
            try {
                // 线程休眠，等待下次重试
                Thread.sleep(interval);
            } catch (InterruptedException ignored) {
                // 如果线程被中断，重新设置中断状态并抛出异常
                Thread.currentThread().interrupt();
                throw e;
            }
            // 记录已经休眠的总时间
            sleptForMillis += interval;
        }


        /**
         * Calculates the time interval to a retry attempt.<br>
         * The interval increases exponentially with each attempt, at a rate of nextInterval *= 1.5
         * (where 1.5 is the backoff factor), to the maximum interval.
         *
         * @return time in milliseconds from now until the next attempt.
         */
        long nextMaxInterval() {
            long interval = (long) (period * Math.pow(1.5, attempt - 1));
            return Math.min(interval, maxPeriod);
        }

        @Override
        public Retryer clone() {
            return new Default(period, maxPeriod, maxAttempts);
        }
    }

    /**
     * Implementation that never retries request. It propagates the RetryableException.
     */
    Retryer NEVER_RETRY = new Retryer() {

        @Override
        public void continueOrPropagate(RetryableException e) {
            throw e;
        }

        @Override
        public Retryer clone() {
            return this;
        }
    };
}
