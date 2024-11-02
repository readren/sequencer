package readren.taskflow

import Doer.ExceptionReport

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object Doer {
	/** Specifies what an instance of [[Doer]] require to function properly. */
	trait Assistant {
		/**
		 * The implementation should queue the execution of all the [[Runnable]]s this method receives on the same single-thread executor. Note that "single" does not imply "same". The thread may change.
		 * From now on said executor will be called "the doer's single-thread executor" or DoSiThEx for short.
		 * The implementation should guarantee that passed [[Runnable]]s are executed one after the other (no more than one [[Runnable]] will be active at any given time) and preferably in the order of submission.
		 * If the call is executed by the DoSiThEx the [[Runnable]]'s execution will not start until the DoSiThEx completes its current execution and gets free to start a new one.
		 * The implementation should not throw non-fatal exceptions.
		 * The implementation should be thread-safe.
		 *
		 * All the deferred actions preformed by the [[Task]] operations are executed by calling this method unless the particular operation documentation says otherwise. That includes not only the call-back functions like `onComplete` but also all the functions, procedures, predicates, and by-name parameters they receive as.
		 * */
		def queueForSequentialExecution(runnable: Runnable): Unit

		/**
		 * The implementation should report the received [[Throwable]] somehow. Preferably including a description that identifies the provider of the DoSiThEx used by [[queueForSequentialExecution]] and mentions that the error was thrown by a deferred procedure programmed by means of a [[Task]].
		 * The implementation should not throw non-fatal exceptions.
		 * The implementation should be thread-safe.
		 * */
		def reportFailure(cause: Throwable): Unit
	}

	class ExceptionReport(message: String, cause: Throwable) extends RuntimeException(message, cause)
}

/**
 * Encloses the [[Task]]s instances that are executed by the same single-thread executor, which is called the DoSiThEx.
 * All the deferred actions preformed by the operations of the [[Task]]s enclosed by this [[Doer]] are executed by calling the [[queueForSequentialExecution]] method unless the method documentation says otherwise. That includes not only the call-back functions like `onComplete` but also all the functions, procedures, predicates, and by-name parameters they receive.
 * ==Note:==
 * At the time of writing, almost all the operations and classes in this source file are thread-safe and may function properly on any kind of execution context. The only exceptions are the classes [[CombinedTask]] and [[Commitment]], which could be enhanced to support concurrency. However, given that a design goal was to allow [[Task]] and the functions their operators receive to close over variables in code sections guaranteed to be executed solely by the DoSiThEx (doer's single-threaded executor), the effort and cost of making them concurrent would be unnecessary.
 * See [[Doer.Assistant.queueForSequentialExecution()]].
 *
 * @define DoSiThEx DoSiThEx (doer's single-thread executor)
 * @define onCompleteExecutedByDoSiThEx The `onComplete` callback passed to `engage` is always, with no exception, executed by this $DoSiThEx. This is part of the contract of the [[Task]] trait.
 * @define threadSafe This method is thread-safe.
 * @define isExecutedByDoSiThEx Executed within the DoSiThEx (doer's single-thread executor).
 * @define unhandledErrorsArePropagatedToTaskResult The call to this routine is guarded with try-catch. If it throws a non-fatal exception it will be caught and the [[Task]] will complete with a [[Failure]] containing the error.
 * @define unhandledErrorsAreReported The call to this routine is guarded with a try-catch. If the evaluation throws a non-fatal exception it will be caught and reported with [[Doer.Assistant.reportFailure()]].
 * @define notGuarded CAUTION: The call to this function is NOT guarded with a try-catch. If its evaluation terminates abruptly the duty will never complete. The same occurs with all routines received by [[Duty]] operations. This is one of the main differences with [[Task]] operation.
 * @define maxRecursionDepthPerExecutor Maximum recursion depth per executor. Once this limit is reached, the recursion continues in a new executor. The result does not depend on this parameter as long as no [[StackOverflowError]] occurs.
 * @define isRunningInDoSiThEx indicates whether the caller is certain that this method is being executed within the $DoSiThEx. If there is no such certainty, the caller should set this parameter to `false` (or don't specify a value). This flag is useful for those [[Task]]s whose first action can or must be executed in the DoSiThEx, as it informs them that they can immediately execute said action synchronously. This method is thread-safe when this parameter value is false.
 */
trait Doer(assistant: Doer.Assistant) { thisDoer =>


	/**
	 * Queues the execution of the received [[Runnable]] in this $DoSiThEx. See [[Doer.Assistant.queueForSequentialExecution]]
	 * If the call is executed by the DoSiThEx the [[Runnable]]'s execution will not start until the DoSiThEx completes its current execution and gets free to start a new one.
	 *
	 * All the deferred actions preformed by the [[Task]] operations are executed by calling this method unless the particular operation documentation says otherwise. That includes not only the call-back functions like `onComplete` but also all the functions, procedures, predicates, and by-name parameters they receive as.
	 * This function only makes sense to call:
	 *		- from an action that is not executed by this DoSiThEx (the callback of a [[Future]], for example);
	 *		- or to avoid a stack overflow by continuing the recursion in a new execution.
	 *
	 * ==Note:==
	 * Is more efficient than the functionally equivalent: {{{ Task.mine(runnable.run).attemptAndForget(); }}}.
	 */
	inline def queueForSequentialExecution(inline procedure: => Unit): Unit = {
		${ DoerMacros.queueForSequentialExecutionImpl('assistant, 'procedure) }
	}

	inline def reportFailure(cause: Throwable): Unit =
		${ DoerMacros.reportFailureImpl('assistant, 'cause) }

	/**
	 * An [[ExecutionContext]] that uses the $DoSiThEx. See [[Doer.assistant.queueForSequentialExecution]] */
	object ownSingleThreadExecutionContext extends ExecutionContext {
		def execute(runnable: Runnable): Unit = queueForSequentialExecution(runnable.run())

		def reportFailure(cause: Throwable): Unit = thisDoer.reportFailure(cause)
	}

	/////////////// DUTY ///////////////

	/**
	 * Encapsulates one or more chained actions and provides operations to declaratively build complex duties from simpler ones.
	 * This tool eliminates the need for state variables that determine the decision-making flow, as the code structure itself indicates the execution order.
	 *
	 * This tool was created to simplify the implementation of an actor that needs to perform multiple duties simultaneously (as opposed to a finite state machine), because the overhead of separating them into different actors would be significant.
	 *
	 * Instances of [[Duty]] whose result is always the same follow the monadic laws. However, if the result depends on mutable variables or timing, these laws may be broken.
	 * For example, if a duty `d` closes over a mutable variable from the environment that affects its execution result, the equality of two supposedly equivalent expressions like {{{t.flatMap(f).flatMap(g) == t.flatMap(a => f(a).flatMap(g))}}} could be compromised. This would depend on the timing of when the variable is mutated—specifically if the mutation occurs between the start and end of execution.
	 * This does not mean that [[Duty]] implementations (and the routines their operations receive) must avoid closing over mutable variables altogether. Rather, it highlights that if strict adherence to monadic laws is required by your business logic, you should ensure that the mutable variable is not modified during task execution.
	 * For deterministic behavior, it's sufficient that any closed-over mutable variable is only mutated and accessed by actions executed in sequence. This is why the contract centralizes execution in the $DoSiThEx: to maintain determinism, even when closing over mutable variables, provided they are mutated and accessed solely within the $DoSiThEx.
	 *
	 * Design note: [[Duty]] is a member of [[Doer]] to ensure that access to [[Duty]] instances is restricted to the section of code where the owning [[Doer]] is exposed.
	 *
	 * CAUTION: Unlike [[Task]], [[Duty]] does NOT support failures. And unlike [[Task]], the call to routines received by its operations is not guarded with a try-catch. Therefore, unlike [[Task]], any unhandled exception thrown during the execution of a [[Duty]] will break the expected flow and the duty will never complete.
	 * It is recommended to use [[Task]] instead of [[Duty]] unless efficiency is a concern.
	 *
	 * @tparam A the type of result obtained when executing this duty.
	 */
	trait Duty[+A] { thisDuty =>
		/**
		 * This method performs the actions represented by the duty and calls `onComplete` within the $DoSiThEx context when the task finishes, regardless of whether it succeeds or fails.
		 *
		 * The implementation may assume this method is invoked within the $DoSiThEx.
		 *
		 * Any non-fatal exceptions thrown by this method must be caught, except those thrown by the  provided callback.
		 * If a non-fatal exception originates from a routine passed in the class constructor, including those captured over a closure, it should either be propagated to the task's result or reported using [[assistant.reportFailure]] if propagation is not feasible.
		 *
		 * It is critical to note that exceptions thrown by the callback should never be caught.
		 *
		 * This method is the sole primitive operation of this trait; all other methods are derived from it.
		 *
		 * @param onComplete The callback that must be invoked upon the completion of this task. The implementation should call this callback
		 * within the $DoSiThEx context.
		 *
		 * The implementation may assume that `onComplete` will either terminate normally or
		 * fatally, but will not throw non-fatal exceptions.
		 */
		protected def engage(onComplete: A => Unit): Unit

		/** A bridge to access the [[engage]] method from macros in [[DoerMacros]] and sibling classes. */
		private[taskflow] inline final def engagePortal(onComplete: A => Unit): Unit = engage(onComplete)

		/**
		 * Triggers the execution of this [[Duty]].
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @param onComplete called when the execution of this duty completes.
		 * Must not throw non-fatal exceptions: `onComplet` must either terminate normally or fatally, but never with a non-fatal exception.
		 * Note that if it terminates abruptly the `onComplete` will never be called.
		 * $isExecutedByDoSiThEx
		 */
		inline final def trigger(inline isRunningInDoSiThEx: Boolean = false)(inline onComplete: A => Unit): Unit = {
			${ DoerMacros.triggerImpl('isRunningInDoSiThEx, 'assistant, 'thisDuty, 'onComplete) }
		}

		/** Triggers the execution of this [[Task]] ignoring the result.
		 *
		 * $threadSafe
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 */
		inline final def triggerAndForget(isRunningInDoSiThEx: Boolean = false): Unit =
			trigger(isRunningInDoSiThEx)(_ => {})


		/**
		 * Processes the result of this [[Duty]] once it is completed for its side effects.
		 *
		 * @param consumer called with this task result when it completes. $isExecutedByDoSiThEx $notGuarded
		 */
		inline final def foreach(consumer: A => Unit): Duty[Unit] = new ForEach[A](thisDuty, consumer)

		/**
		 * Transforms this [[Duty]] by applying the given function to the result.
		 * ===Detailed behavior===
		 * Creates a [[Duty]] that, when executed, will trigger the execution of this duty and apply `f` to its result.
		 *
		 * $threadSafe
		 * 
		 * @param f a function that transforms the result of this task, when it is successful.
		 *
		 * $isExecutedByDoSiThEx
		 *
		 * $notGuarded
		 */		
		inline final def map[B](f: A => B): Duty[B] = new Map(thisDuty, f)

		/**
		 * Composes this [[Duty]] with a second one that is built from the result of this one.
		 * ===Detailed behavior===
		 * Creates a [[Duty]] that, when executed, it will:
		 *		- Trigger the execution of this task and applies the `taskBBuilder` function to its result.
		 *		- Then triggers the execution of the built duty and completes with its result.
		 *
		 * $threadSafe
		 *
		 * @param f a function that receives the result of this task and returns the task to be executed next.
		 *
		 * $isExecutedByDoSiThEx
		 *
		 * $notGuarded
		 */
		inline final def flatMap[B](f: A => Duty[B]): Duty[B] = new FlatMap(thisDuty, f)

		/** Transforms this [[Duty]] into a [[Task]]
		 * ===Detailed behavior===
		 * Creates a [[Task]] that, when executed, triggers the execution of this [[Duty]] and completes with its result, which will always be successful.
		 * @return a [[Task]] whose result is the result of this task wrapped inside a [[Success]]. */
		inline final def toTask: Task[A] = new ToTask(thisDuty)

		/**
		 * Triggers the execution of this [[Task]] and returns a [[Future]] containing its result wrapped inside a [[Success]].
		 *
		 * Is equivalent to {{{ transform(Success.apply).toFuture(isRunningInDoSiThEx) }}}
		 *
		 * Useful when failures need to be propagated to the next for-expression (or for-binding).
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @return a [[Future]] that will be completed when this [[Task]] is completed.
		 */
		def toFutureHardy(isRunningInDoSiThEx: Boolean = false): Future[A] = {
			val promise = Promise[A]()
			trigger(isRunningInDoSiThEx)(a => promise.success(a))
			promise.future
		}

		/**
		 * Repeats this duty until applying the received function yields [[Maybe.some]].
		 * ===Detailed description===
		 * Creates a [[Duty]] that, when executed, it will:
		 * - execute this duty producing the result `a`
		 * - apply `condition` to `(completedCycles, a)`. If the evaluation finishes with:
		 *      - `some(b)`, completes with `b`
		 *      - `empty`, goes back to the first step.
		 *
		 * $threadSafe
		 *
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that decides if the loop continues or not based on:
		 *  - the number of already completed cycles,
		 *  - and the result of the last execution of the `dutyA`.
		 * The loop ends when this function returns a [[Maybe.some]]. Its content will be the final result.
		 * @return a new [[Duty]] that, when executed, repeatedly executes this duty and applies the `condition` to the duty's result until the function's result is [[Maybe.some]]. The result of this duty is the contents of said [[Maybe]].
		 */
		final inline def repeatedUntilSome[B](maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Maybe[B]): Duty[B] =
			new RepeatUntilSome(thisDuty, maxRecursionDepthPerExecutor)(condition)

		/**
		 * Like [[repeatedUntilSome]] but the condition is a [[PartialFunction]] instead of a function that returns [[Maybe]].
		 */
		final inline def repeatedUntilDefined[B](maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, A), B]): Duty[B] =
			repeatedUntilSome(maxRecursionDepthPerExecutor)(Function.untupled(Maybe.liftPartialFunction(pf)))

		/**
		 * Repeats this [[Duty]] while the given function returns [[Maybe.empty]].
		 * ===Detailed behavior===
		 * Returns a [[Duty]] that, when executed, it will:
		 *  - Apply the `condition` function to `(n, s0)` where `n` is the number of already completed evaluations of it (starts with zero).
		 *  - If the evaluation returns:
		 *      - `some(b)`, completes with `b`.
		 *      - `empty`, executes the `dutyA` and goes back to the first step replacing `s0` with the result.
		 *
		 * $threadSafe
		 *
		 * @param s0 the value passed as second parameter to `condition` the first time it is evaluated.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `da0` if no cycle has been done yet.
		 * @tparam S a supertype of `A`
		 * @tparam B the type of the result of this duty.
		 */
		final inline def repeatedWhileEmpty[S >: A, B](s0: S, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, S) => Maybe[B]): Duty[B] =
			new RepeatWhileEmpty[S, B](thisDuty, s0, maxRecursionDepthPerExecutor)(condition)

		/**
		 * Returns a duty that, when executed, repeatedly executes this [[Duty]] while a [[PartialFunction]] is undefined.
		 * ===Detailed behavior===
		 * Returns a [[Duty]] that, when executed, it will:
		 *  - Check if the partial function is defined in `(n, s0)` where `n` is the number of already completed evaluations of it (starts with zero).
		 *  - If it is undefined, executes the `dutyA` and goes back to the first step replacing `s0` with the result.
		 *  - If it is defined, evaluates it and completes with the result.
		 *
		 * $threadSafe
		 *
		 * @param s0 the value passed as second parameter to `condition` the first time it is evaluated.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param pf determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `da0` if no cycle has been done yet.
		 * @tparam S a supertype of `A`
		 * @tparam B the type of the result of this duty.
		 */
		final inline def repeatedWhileUndefined[S >: A, B](s0: S, maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, S), B]): Duty[B] =
			repeatedWhileEmpty(s0, maxRecursionDepthPerExecutor)(Function.untupled(Maybe.liftPartialFunction(pf)))

		/**
		 * Wraps this duty into another that belongs to another [[Doer]].
		 * Useful to chain [[Duty]]'s operations that involve different [[Doer]]s.
		 * ===Detailed behavior===
		 * Returns a duty that, when executed, it will execute this duty within this [[Doer]]'s $DoSiThEx and complete with the same result.
		 *
		 * $threadSafe
		 *
		 * @param otherDoer the [[Doer]] to which the returned duty will belong.
		 */
		def onBehalfOf(otherDoer: Doer): otherDoer.Duty[A] =
			otherDoer.Duty.foreign(thisDoer)(this)

		/** Casts the type-path of this [[Duty]] to the received [[Doer]].
		 * This operation does nothing at runtime. It merely satisfies the compiler to allow operations on [[Duty]]s with distinct type paths.
		 */
		def castTypePath[E <: Doer](doer: E): doer.Duty[A] = {
			assert(thisDoer eq doer)
			this.asInstanceOf[doer.Duty[A]]
		}
	}

	object Duty {
		val never: Duty[Nothing] = NotEver

		/** Creates a [[Duty]] whose result is calculated at the call site even before the duty is constructed.
		 * $threadSafe
		 *
		 * @param a the already calculated result of the returned [[Duty]]. */
		inline def ready[A](a: A): Duty[A] = new Ready(a)

		/** Creates a [[Duty]] whose result is calculated withing the $DoSiThEx.
		 * ===Detailed behavior===
		 * Creates a duty that, when executed, evaluates the `resultSupplier` within the $DoSiThEx. If the evaluation finishes:
		 *		- abruptly, will never complete.
		 *		- normally, completes with the evaluation's result.
		 *
		 * $$threadSafe
		 *
		 * @param supplier the supplier of the result. $isExecutedByDoSiThEx $notGuarded
		 * @return the task described in the method description.
		 */
		inline def mine[A](supplier: () => A): Duty[A] = new Mine(supplier)

		/** Creates a [[Duty]] that, when executed, triggers the execution of a duty that belongs to another [[Doer]] within that [[Doer]]'s $DoSiThEx.
		 * $threadSafe
		 *
		 * @param foreignDoer the [[Doer]] to whom the `foreignTask` belongs.
		 * @return a duty that belongs to this [[Doer]] that completes when the `foreignDuty` is completed by the `foreignDoer`. */
		inline def foreign[A](foreignDoer: Doer)(foreignDuty: foreignDoer.Duty[A]): Duty[A] = new DelegateTo[A](foreignDoer, foreignDuty)

		/**
		 * Creates a [[Duty]] that, when executed, simultaneously triggers the execution of two tasks and returns their results combined with the received function.
		 * Given the single thread nature of [[Doer]] this operation only has sense when the received duties are a chain of actions that involve timers, foreign, or alien duties.
		 * ===Detailed behavior===
		 * Creates a new [[Duty]] that, when executed:
		 *		- triggers the execution of both: `dutyA` and `dutyB`
		 *		- when both are completed, completes with the value that results of applying the function `f` to their results.
		 *
		 * $threadSafe
		 *
		 * @param dutyA a task
		 * @param dutyB a task
		 * @param f the function that combines the results of the `taskA` and `taskB`. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return the task described in the method description.
		 */
		inline def combine[A, B, C](dutyA: Duty[A], dutyB: Duty[B])(f: (A, B) => C): Duty[C] =
			new ForkJoin(dutyA, dutyB, f)

		/**
		 * Creates a [[Duty]] that, when executed, simultaneously triggers the execution of all the [[Duty]]s in the received list, and completes with a list containing their results in the same order.
		 *
		 * $threadSafe
		 *
		 * @param duties the list of [[Duty]]s that the returned task will trigger simultaneously to combine their results.
		 * @return the duty described in the method description.
		 *
		 * */
		def sequence[A](duties: List[Duty[A]]): Duty[List[A]] = {
			@tailrec
			def loop(incompleteResult: Duty[List[A]], remainingDuties: List[Duty[A]]): Duty[List[A]] = {
				remainingDuties match {
					case Nil =>
						incompleteResult
					case head :: tail =>
						val lessIncompleteResult = combine(head, incompleteResult) { (a, as) => a :: as }
						loop(lessIncompleteResult, tail)
				}
			}

			duties.reverse match {
				case Nil => ready(Nil)
				case lastDuty :: previousDuties => loop(lastDuty.map(List(_)), previousDuties);
			}
		}

		/** Creates a new [[Duty]] that, when executed, repeatedly constructs a duty and executes it while a condition returns [[Right]].
		 * ==Detailed behavior:==
		 * Gives a new [[Duty]] that, when executed, it will:
		 *  - Apply the function `condition` to `(completedCycles, a0)`, and if it returns:
		 *		- a `Left(b)`, completes with `b`.
		 *  	- a `Right(taskA)`, executes the `taskA` goes back to the first step, replacing `a0` with the result.
		 *
		 * $threadSafe
		 *
		 * @param a0 the initial iteration state.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that, based on the `completedExecutionsCounter` and the iteration's state `a`, determines if the loop should end or otherwise creates the [[Duty]] to execute in the next iteration.
		 * @tparam A the type of the state passed from an iteration to the next.
		 * @tparam B the type of the result of created [[Duty]]
		 */
		def whileRightRepeat[A, B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Either[B, Duty[A]]): Duty[B] =
			new WhileRightRepeat[A, B](a0, maxRecursionDepthPerExecutor)(condition)

		/** Creates a new [[Duty]] that, when executed, repeatedly constructs and executes tasks until the `condition` is met.
		 * ===Detailed behavior:===
		 * Gives a new [[Duty]] that, when executed, it will:
		 * - Apply the function `condition` to `(n,a0)` where n is the number of cycles already done. Then executes the resulting `task` and if its result is:
		 *			- `Left(tryB)`, completes with `tryB`.
		 *			- `Right(a1)`, goes back to the first step replacing `a0` with `a1`.
		 *
		 * $threadSafe
		 *
		 * @param a0 the initial iteration state.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that, based on the `completedExecutionsCounter` and the iteration's state (which starts with `a0`), determines if the loop should end or otherwise creates the [[Duty]] to execute in the next iteration.
		 * @tparam A the type of the state passed from an iteration to the next.
		 * @tparam B the type of the result of created [[Duty]]
		 */
		inline def repeatUntilLeft[A, B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Duty[Either[B, A]]): Duty[B] =
			new RepeatUntilLeft(a0, maxRecursionDepthPerExecutor)(condition)

		/** Creates a new [[Duty]] that, when executed, repeatedly constructs and executes tasks until it succeeds or `maxRetries` is reached.
		 * ===Detailed behavior:===
		 * When the returned [[Task]] is executed, it will:
		 * 		- Apply the function `taskBuilder` to the number of tries that were already done.
		 * 		- Then executes the returned task and if the result is:
		 *				- `Right(b)`, completes with `Success(b)`.
		 *				- `Left(a)`, compares the retries counter against `maxRetries` and if:
		 *					- `retriesCounter >= maxRetries`, completes with `Left(a)`
		 *					- `retriesCounter < maxRetries`, increments the `retriesCounter` (which starts at zero) and goes back to the first step.
		 *
		 * $threadSafe
		 *
		 * @param maxRetries the maximum number of retries. Note that N retries is equivalent to N+1 attempts. So, a value of zero retries is one attempt.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param taskBuilder function to construct tasks, taking the retry count as input.
		 */
		inline def retryUntilRight[A, B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Duty[Either[A, B]]): Duty[Either[A, B]] =
			new RetryUntilRight[A, B](maxRetries, maxRecursionDepthPerExecutor)(taskBuilder)

		/** Returns a new [[Duty]] that, when executed:
		 * 	- creates and executes a control task and, depending on its result, either:
		 *		- completes.
		 *		- or creates and executes an interleaved task and then goes back to the first step.
		 * WARNING: the execution of the returned duty will never end if the control duty always returns [[Right]].
		 *
		 * $threadSafe
		 *
		 * @param a0 2nd argument passed to `controlTaskBuilder` in the first cycle.
		 * @param b0 3rd argument passed to `controlTaskBuilder` in the first cycle.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param controlTaskBuilder the function that builds the control duty. It takes three parameters:
		 * - the number of already executed interleaved duties.
		 * - the result of the control duty in the previous cycle or `a0` in the first cycle.
		 * - the result of the interleaved duty in the previous cycle or `b0` in the first cycle.
		 * @param interleavedDutyBuilder the function that builds the interleaved duties. It takes two parameters:
		 *		- the number of already executed interleaved duties.
		 *		- the result of the control duty in the current cycle.
		 * */

		def repeatInterleavedUntilLeftDuty[A, B, R](a0: A, b0: B, maxRecursionDepthPerExecutor: Int = 9)(controlTaskBuilder: (Int, A, B) => Duty[Either[R, A]])(interleavedDutyBuilder: (Int, A) => Duty[B]): Duty[R] = {

			repeatUntilLeft[(A, B), R]((a0, b0), maxRecursionDepthPerExecutor) { (completedCycles, ab) =>
				controlTaskBuilder(completedCycles, ab._1, ab._2).flatMap {
					case Left(r) =>
						Duty.ready(Left(r))

					case Right(a) =>
						interleavedDutyBuilder(completedCycles, a).map(b => Right((a, b)))
				}
			}
		}

	}

	final class ForEach[A](cA: Duty[A], consumer: A => Unit) extends Duty[Unit] {
		override def engage(onComplete: Unit => Unit): Unit = cA.engagePortal(onComplete.compose(consumer))

		override def toString: String = deriveToString[ForEach[A]](this)
	}

	final class Map[A, B](cA: Duty[A], f: A => B) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = cA.engagePortal { a => onComplete(f(a)) }

		override def toString: String = deriveToString[Map[A, B]](this)
	}

	final class FlatMap[A, B](cA: Duty[A], f: A => Duty[B]) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = cA.engagePortal { a => f(a).engagePortal(onComplete) }

		override def toString: String = deriveToString[FlatMap[A, B]](this)
	}

	final class ToTask[A](cA: Duty[A]) extends Task[A] {
		override def engage(onComplete: Try[A] => Unit): Unit = cA.engagePortal(onComplete.compose(Success.apply))

		override def toString: String = deriveToString[ToTask[A]](this)
	}

	object NotEver extends Duty[Nothing] {
		override def engage(onComplete: Nothing => Unit): Unit = ()

		override def toString: String = "NotEver"
	}

	final class Ready[A](a: A) extends Duty[A] {
		override def engage(onComplete: A => Unit): Unit = onComplete(a)

		override def toFutureHardy(isRunningInDoSiThEx: Boolean = false): Future[A] = Future.successful(a)

		override def toString: String = deriveToString[Ready[A]](this)
	}

	final class Mine[A](supplier: () => A) extends Duty[A] {
		override def engage(onComplete: A => Unit): Unit = onComplete(supplier())

		override def toString: String = deriveToString[Mine[A]](this)
	}

	final class DelegateTo[A](foreignDoer: Doer, foreignDuty: foreignDoer.Duty[A]) extends Duty[A] {
		override def engage(onComplete: A => Unit): Unit = foreignDuty.engagePortal(a => queueForSequentialExecution(onComplete(a)))

		override def toString: String = deriveToString[DelegateTo[A]](this)
	}

	/** A [[Duty]] that, when executed:
	 *		- triggers the execution of both: `taskA` and `taskB`;
	 *		- when both are completed, completes with the result of applying the function `f` to their results.
	 *
	 * $threadSafe
	 * $onCompleteExecutedByDoSiThEx
	 */
	final class ForkJoin[+A, +B, +C](dutyA: Duty[A], dutyB: Duty[B], f: (A, B) => C) extends Duty[C] {
		override def engage(onComplete: C => Unit): Unit = {
			var ma: Maybe[A] = Maybe.empty;
			var mb: Maybe[B] = Maybe.empty;
			dutyA.engagePortal { a =>
				mb.fold {
					ma = Maybe.some(a)
				} { b => onComplete(f(a, b)) }
			}
			dutyB.engagePortal { b =>
				ma.fold {
					mb = Maybe.some(b)
				} { a => onComplete(f(a, b)) }
			}
		}

		override def toString: String = deriveToString[ForkJoin[A, B, C]](this)
	}

	/**
	 * A [[Duty]] that executes the received duty until applying the received function yields [[Maybe.some]].
	 * ===Detailed description===
	 * A [[Duty]] that, when executed, it will:
	 *		- execute the `dutyA` producing the result `a`
	 *		- apply `condition` to `(completedCycles, a)`. If the evaluation finishes with:
	 *			- `some(b)`, completes with `b`
	 *			- `empty`, goes back to the first step.
	 *
	 * @param dutyA the duty to be repeated.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param condition function that decides if the loop continues or not based on:
	 *		- the number of already completed cycles,
	 *		- and the result of the last execution of the `dutyA`.
	 * The loop ends when this function returns a [[Maybe.some]]. Its content will be the final result of this task.
	 */
	final class RepeatUntilSome[+A, +B](dutyA: Duty[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Maybe[B]) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = {
			def loop(completedCycles: Int, recursionDepth: Int): Unit = {
				dutyA.engagePortal { a =>
					condition(completedCycles, a).fold {
						if (recursionDepth < maxRecursionDepthPerExecutor) {
							loop(completedCycles + 1, recursionDepth + 1)
						} else {
							queueForSequentialExecution(loop(completedCycles + 1, 0))
						}
					}(onComplete)
				}
			}

			loop(0, 0)
		}

		override def toString: String = deriveToString[RepeatUntilSome[A, B]](this)
	}

	/**
	 * Duty that, when executed, repeatedly executes a duty while a condition returns [[Maybe.empty]].
	 * ===Detailed behavior:===
	 * When this [[Duty]] is executed, it will:
	 *  - Apply the `condition` function to `(n, a0)` where `n` is the number of already completed evaluations.
	 *  - If the evaluation returns:
	 *  	- `some(b)`, completes with `b`.
	 *  	- `empty`, executes the `dutyA` and repeats the condition.
	 *
	 * @param dutyA the task to be repeated
	 * @param a0 the value passed as the second parameter to `condition` the first time it is evaluated.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param condition determines if a new cycle should be performed based on the number of already completed cycles and the last result of `dutyA` or `a0` if no cycle has been completed.
	 */
	final class RepeatWhileEmpty[+A, +B](dutyA: Duty[A], a0: A, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Maybe[B]) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = {
			def loop(completedCycles: Int, lastDutyResult: A, recursionDepth: Int): Unit = {
				condition(completedCycles, lastDutyResult).fold {
					dutyA.engagePortal { newA =>
						if recursionDepth < maxRecursionDepthPerExecutor then loop(completedCycles + 1, newA, recursionDepth + 1)
						else queueForSequentialExecution(loop(completedCycles + 1, newA, 0))
					}
				}(onComplete)
			}

			loop(0, a0, 0)
		}

		override def toString: String = deriveToString[RepeatWhileEmpty[A, B]](this)
	}

	/**
	 * Duty that, when executed, repeatedly constructs and executes duties as long as the `condition` is met.
	 * ===Detailed behavior:===
	 * When this [[Duty]] is executed, it will:
	 *  - Apply the function `checkAndBuild` to `(n, a0)` where `n` is the number of completed cycles.
	 *  	- If it returns a `Left(b)`, completes with `b`.
	 *  	- If it returns `Right(dutyA)`, executes `dutyA` and repeats the cycle replacing `a0` with the result.
	 *
	 * @param a0 the initial value used in the first call to `checkAndBuild`.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param checkAndBuild function that takes completed cycles count and last duty result, returning an `Either[B, Duty[A]]`.
	 */
	final class WhileRightRepeat[+A, +B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(checkAndBuild: (Int, A) => Either[B, Duty[A]]) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = {
			def loop(completedCycles: Int, lastDutyResult: A, recursionDepth: Int): Unit = {
				checkAndBuild(completedCycles, lastDutyResult) match {
					case Left(b) => onComplete(b)
					case Right(dutyA) =>
						dutyA.engagePortal { newA =>
							if recursionDepth < maxRecursionDepthPerExecutor then loop(completedCycles + 1, newA, recursionDepth + 1)
							else queueForSequentialExecution(loop(completedCycles + 1, newA, 0))
						}
				}
			}

			loop(0, a0, 0)
		}

		override def toString: String = deriveToString[WhileRightRepeat[A, B]](this)
	}

	/**
	 * Duty that, when executed, repeatedly constructs and executes duties until the result is [[Left]] or a failure occurs.
	 * ===Detailed behavior:===
	 * When this [[Duty]] is executed, it will:
	 *  - Apply the function `buildAndCheck` to `(n, a0)` where `n` ìs the number of completed cycles. Then executes the built duty and, if the result is:
	 *  	- `Left(b)`, completes with `b`.
	 *  	- `Right(a1)`, repeats the cycle replacing `a0` with `a1`.
	 *
	 * @param a0 the initial value used in the first call to `buildAndCheck`.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param buildAndCheck function that takes completed cycles count and last duty result, and returns a new duty that yields an `Either[B, A]`.
	 */
	final class RepeatUntilLeft[+A, +B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(buildAndCheck: (Int, A) => Duty[Either[B, A]]) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = {
			def loop(executionsCounter: Int, lastDutyResult: A, recursionDepth: Int): Unit = {
				val duty = buildAndCheck(executionsCounter, lastDutyResult)
				duty.engagePortal {
					case Left(b) => onComplete(b)
					case Right(a) =>
						if recursionDepth < maxRecursionDepthPerExecutor then loop(executionsCounter + 1, a, recursionDepth + 1)
						else queueForSequentialExecution(loop(executionsCounter + 1, a, 0))
				}
			}

			loop(0, a0, 0)
		}

		override def toString: String = deriveToString[RepeatUntilLeft[A, B]](this)
	}

	/** Task that, when executed, repeatedly constructs and executes tasks until the result is [[Right]] or the `maxRetries` is reached.
	 * ===Detailed behavior:===
	 * When it is executed, it will:
	 *  - Apply the function `taskBuilder` to the number of tries that were already done. If the evaluation completes:
	 *  	- Abruptly, completes with the cause.
	 *  		- Normally, executes the returned task and if the result is:
	 *  			- `Failure(cause)`, completes with `Failure(cause)`.
	 *  			- `Success(Right(b))`, completes with `Success(b)`.
	 *  			- `Success(Left(a))`, compares the retries counter against `maxRetries` and if:
	 *  				- `retriesCounter >= maxRetries`, completes with `Left(a)`
	 *  				- `retriesCounter < maxRetries`, increments the `retriesCounter` (which starts at zero) and goes back to the first step.
	 */
	final class RetryUntilRight[+A, +B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Duty[Either[A, B]]) extends Duty[Either[A, B]] {
		override def engage(onComplete: Either[A, B] => Unit): Unit = {
			/**
			 * @param attemptsAlreadyMade the number attempts already made.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(attemptsAlreadyMade: Int, recursionDepth: Int): Unit = {
				val task: Duty[Either[A, B]] = taskBuilder(attemptsAlreadyMade)

				task.engagePortal {
					case rb@(_: Right[A, B]) =>
						onComplete(rb)
					case la@Left(a) =>
						if (attemptsAlreadyMade >= maxRetries) {
							onComplete(la)
						} else if (recursionDepth < maxRecursionDepthPerExecutor) {
							loop(attemptsAlreadyMade + 1, recursionDepth + 1)
						} else {
							queueForSequentialExecution(loop(attemptsAlreadyMade + 1, 0))
						}
				}
			}

			loop(0, 0)
		}

		override def toString: String = deriveToString[RetryUntilRight[A, B]](this)
	}

	////////////// COVENANT ///////////////

	/** A covenant to complete a [[Duty]].
	 * [[Covenant]] is to [[Duty]] as [[Commitment]] is to [[Task]], and as [[scala.concurrent.Promise]] is to [[scala.concurrent.Future]]
	 * */
	final class Covenant[A] { thisCovenant =>
		private var oResult: Maybe[A] = Maybe.empty;
		private var onCompletedObservers: List[A => Unit] = Nil;

		/** @return true if this [[Covenant]] was fulfilled; or false if it is still pending. */
		inline def isCompleted: Boolean = this.oResult.isDefined;

		/** @return true if this [[Covenant]] is still pending; or false if it was completed. */
		inline def isPending: Boolean = this.oResult.isEmpty;

		/** The [[Duty]] that this [[Covenant]] promises to fulfill. This duty is completed when the [[Covenant]] is fulfilled, either immediately by calling [[fulfill]], or after the completion of a specified duty by calling [[fulfillWith]]. */
		val duty: Duty[A] = (onComplete: A => Unit) => {
			thisCovenant.oResult.fold {
				thisCovenant.onCompletedObservers = onComplete :: thisCovenant.onCompletedObservers
			}(onComplete)
		}

		/** Provokes that the [[Duty]] that this [[Covenant]] promises to complete to be completed with the received `result`.
		 *
		 * @param result the result that the [[task]] this [[Commitment]] promised to complete . */
		def fulfill(result: A)(onAlreadyCompleted: A => Unit = _ => ()): this.type = {
			queueForSequentialExecution {
				oResult.fold {
					this.oResult = Maybe.some(result);
					this.onCompletedObservers.foreach(_(result));
					// la lista de observadores quedó obsoleta. Borrarla para minimizar posibilidad de memory leak.
					this.onCompletedObservers = Nil
				} (onAlreadyCompleted)
			};
			this;
		}

		/** Triggers the execution of the specified [[Duty]] and completes the [[Duty]] that this [[Covenant]] promises to fulfill with the result of the specified duty once it finishes. */
		def fulfillWith(dutyA: Duty[A])(onAlreadyCompleted: A => Unit = _ => ()): this.type = {
			if (dutyA ne this.duty)
				dutyA.trigger()(result => fulfill(result)(onAlreadyCompleted));
			this
		}
	}



	///////////// TASK //////////////

	/**
	 * A hardy version of [[Duty]].
	 * Advantages of [[Task]] compared to [[Duty]]:
	 *		- results are wrapped withing a [[Try]] which allow the support of failed results.
	 *		- the call to the routines received by the operations are guarded with a try-catch, which allows to propagate failures through [[Task]] chains.
	 *		- can encapsulate a [[Future]] making interoperability with them easier.
	 *
	 * Design note: [[Task]] and [[Duty]] are a member of [[Doer]] to ensure that the access to instances of them is restricted to the section of code where the owning [[Doer]] is exposed.
	 *
	 * @tparam A the type of the result obtained when executing this task.
	 */
	trait Task[+A] extends Duty[Try[A]] { thisTask =>

		/** Triggers the execution of this [[Task]] and returns a [[Future]] of its result.
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @return a [[Future]] that will be completed when this [[Task]] is completed.
		 */
		def toFuture(isRunningInDoSiThEx: Boolean = false): Future[A] = {
			val promise = Promise[A]()
			trigger(isRunningInDoSiThEx)(promise.complete)
			promise.future
		}

		/** Triggers the execution of this [[Task]] noticing faulty results.
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @param errorHandler called when the execution of this task completes with a failure. $isExecutedByDoSiThEx $unhandledErrorsAreReported
		 */
		def triggerAndForgetHandlingErrors(isRunningInDoSiThEx: Boolean = false)(errorHandler: Throwable => Unit): Unit =
			trigger(isRunningInDoSiThEx) {
				case Failure(e) =>
					try errorHandler(e) catch {
						case NonFatal(cause) => reportFailure(cause)
					}
				case _ => ()
			}

		/**
		 * Processes the result of the task once it is completed, for its side effects.
		 * ===Detailed behavior===
		 * A [[Task]] that, when executed, it will:
		 *		- trigger the execution of `taskA` and if the result is:
		 *			- a [[Failure]], completes with that failure without calling `consumer`.	
		 *			- `Success(a)`, tries to apply the `consumer` to `a`. If the evaluation finishes:
		 *				- abruptly, completes with a [[Failure]] containing the cause.
		 *				- normally, completes with `Success(())`.
		 *
		 * @param consumer called with this task result when it completes, if it ever does.
		 */
		inline def consume(consumer: Try[A] => Unit): Task[Unit] =
			new Consume[A](thisTask, consumer)

		/**
		 * Processes the result of the task once it is completed successfully, for its side effects.
		 * WARNING: `consumer` will not be called if this task completes with a failure.
		 *
		 * @param consumer called with this task result when it completes, if it ever does.
		 * */
		final def foreach(consumer: A => Unit): Task[Unit] =
			consume {
				case Success(a) => consumer(a)
				case _ => ()
			}

		/**
		 * Transform this task by applying the given function to the result. Analogous to [[Future.transform]]
		 * ===Detailed description===
		 * Creates a [[Task]] that, when executed, triggers the execution of this task and applies the received `resultTransformer` to its result. If the evaluation finishes:
		 *		- abruptly, completes with the cause.
		 *		- normally, completes with the result of the evaluation.
		 *
		 * $threadSafe
		 *
		 * @param resultTransformer applied to the result of the `originalTask` to obtain the result of this task.
		 *
		 * $isExecutedByDoSiThEx
		 *
		 * $unhandledErrorsArePropagatedToTaskResult
		 */
		inline final def transform[B](resultTransformer: Try[A] => Try[B]): Task[B] = new Transform(thisTask, resultTransformer)


		/**
		 * Composes this [[Task]] with a second one that is built from the result of this one. Analogous to [[Future.transformWith]].
		 * ===Detailed behavior===
		 * Creates a [[Task]] that, when executed, it will:
		 *		- Trigger the execution of this task and apply the `taskBBuilder` function to its result. If the evaluation finishes:
		 *			- abruptly, completes with the cause.
		 *			- normally with `taskB`, triggers the execution of `taskB` and completes with its result.
		 *
		 * $threadSafe
		 *
		 * @param taskBBuilder a function that receives the result of `taskA` and builds the task to be executed next. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 */
		inline final def transformWith[B](taskBBuilder: Try[A] => Task[B]): Task[B] =
			new Compose(thisTask, taskBBuilder)


		/**
		 * Transforms this task by applying the given function to the result if it is successful. Analogous to [[Future.map]].
		 * See [[recover]] and [[toDuty]] if you want to transform the failures; and [[transform]] if you want to transform both, successful and failed ones.
		 * ===Detailed behavior===
		 * Creates a [[Task]] that, when executed, it will trigger the execution of this task and, if the result is:
		 *		- `Failure(e)`, completes with that failure.
		 *		- `Success(a)`, apply `f` to `a` and if the evaluation finishes:
		 *			- abruptly with `cause`, completes with `Failure(cause)`.
		 *			- normally with value `b`, completes with `Success(b)`.
		 *
		 * $threadSafe
		 *
		 * @param f a function that transforms the result of this task, when it is successful. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 */
		inline final def map[B](f: A => B): Task[B] = transform(_ map f)

		/**
		 * Composes this [[Task]] with a second one that is built from the result of this one, but only when this one is successful. Analogous to [[Future.flatMap]].
		 * ===Detailed behavior===
		 * Creates a [[Task]] that, when executed, it will:
		 *		- Trigger the execution of this task and if the result is:
		 *			- `Failure(e)`, completes with that failure.
		 *			- `Success(a)`, applies the `taskBBuilder` function to `a`. If the evaluation finishes:
		 *				- abruptly, completes with the cause.
		 *				- normally with `taskB`, triggers the execution of `taskB` and completes with its result.
		 *
		 * $threadSafe
		 *
		 * @param taskBBuilder a function that receives the result of `taskA`, when it is a [[Success]], and returns the task to be executed next. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 */
		final def flatMap[B](taskBBuilder: A => Task[B]): Task[B] = transformWith {
			case Success(a) => taskBBuilder(a);
			case fail@Failure(_) => Task.ready(fail.castTo[B]);
		}

		/** Needed to support filtering and case matching in for-compressions. The for-expressions (or for-bindings) after the filter are not executed if the [[predicate]] is not satisfied.
		 * Detailed behavior: Gives a [[Task]] that, when executed, it will:
		 *		- executes this [[Task]] and, if the result is a:
		 *			- [[Failure]], completes with that failure.
		 *			- [[Success]], applies the `predicate` to its content and if the evaluation finishes:
		 *				- abruptly, completes with the cause.
		 *				- normally with a `false`, completes with a [[Failure]] containing a [[NoSuchElementException]].
		 *				- normally with a `true`, completes with the result of this task.
		 *
		 * $threadSafe
		 *
		 * @param predicate a predicate that determines which values are propagated to the following for-bindings.
		 * */
		inline final def withFilter(predicate: A => Boolean): Task[A] = new WithFilter(thisTask, predicate)

		/** Applies the side-effecting function to the result of this task, and returns a new task with the result of this task.
		 * This method allows to enforce many callbacks that receive the same value are executed in a specified order.
		 * Note that if one of the chained `andThen` callbacks returns a value or throws an exception, that result is not propagated to the subsequent `andThen` callbacks. Instead, the subsequent `andThen` callbacks are given the result of this task.
		 * ===Detailed description===
		 * Returns a task that, when executed:
		 *		- first executes this task;
		 *		- second applies the received function to the result and, if the evaluation finishes:
		 *			- normally, completes with the result of this task.
		 *			- abruptly with a non-fatal exception, reports the failure cause to [[Doer.Assistant.reportFailure]] and completes with the result of this task.
		 *			- abruptly with a fatal exception, never completes.
		 *
		 * $threadSafe
		 *
		 * @param sideEffect a side-effecting function. The call to this function is wrapped in a try-catch block; however, unlike most other operators, unhandled non-fatal exceptions are not propagated to the result of the returned task. $isExecutedByDoSiThEx
		 */
		final def andThen(sideEffect: Try[A] => Any): Task[A] = {
			transform { tryA =>
				try sideEffect(tryA)
				catch {
					case NonFatal(e) => reportFailure(e)
				}
				tryA
			}
		}
		
		/**
		 * Transforms this [[Task]] into a [[Duty]] applying the given function to transform failure results into successful ones. This is like [[map]] but for the throwable; and like [[recover]] but with a complete function.
		 *
		 * @param exceptionHandler the complete function to apply to the result of this task if it is a [[Failure]]. $isExecutedByDoSiThEx */
		inline final def toDuty[B >: A](exceptionHandler: Throwable => B): Duty[B] = new ToDuty[A, B](thisTask, exceptionHandler)

		/** Transforms this task applying the given partial function to failure results. This is like map but for the throwable; and like [[toDuty]] but with a partial function. Analogous to [[Future.recover]].
		 * ===detailed description===
		 * Returns a new [[Task]] that, when executed, executes this task and if the result is:
		 * 		- a [[Success]] or a [[Failure]] for which `pf` is not defined, completes with the same result.
		 *		- a [[Failure]] for which `pf` is defined, applies `pf` to it and if the evaluation finishes:
		 *			- abruptly, completes with the cause.
		 *			- normally, completes with the result of the evaluation.
		 *
		 * $threadSafe
		 *
		 * @param pf the [[PartialFunction]] to apply to the result of this task if it is a [[Failure]]. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 */
		final def recover[B >: A](pf: PartialFunction[Throwable, B]): Task[B] =
			transform {
				_.recover(pf)
			}

		/** Composes this task with a second one that is built from the result of this one, but only when said result is a [[Failure]] for which the given partial function is defined. This is like flatMap but for the exception. Analogous to [[Future.recoverWith]].
		 * ===detailed description===
		 * Returns a new [[Task]] that, when executed, executes this task and if the result is:
		 * 		- a [[Success]] or a [[Failure]] for which `pf` is not defined, completes with the same result.
		 *		- a [[Failure]] for which `pf` is defined, applies `pf` to it and if the evaluation finishes:
		 *			- abruptly, completes with the cause.
		 *			- normally returning a [[Task]], triggers the execution of said task and completes with its same result.
		 *
		 * $threadSafe
		 *
		 * @param pf the [[PartialFunction]] to apply to the result of this task, if it is a [[Failure]], to build the second task. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 */
		final def recoverWith[B >: A](pf: PartialFunction[Throwable, Task[B]]): Task[B] = {
			transformWith[B] {
				case Failure(t) => pf.applyOrElse(t, (e: Throwable) => new Immediate(Failure(e)));
				case sa@Success(_) => new Immediate(sa);
			}
		}

		/**
		 * Repeats this task until applying the received function yields [[Maybe.some]].
		 * ===Detailed description===
		 * Creates a [[Task]] that, when executed, it will:
		 * - execute this task producing the result `tryA`
		 * - apply `condition` to `(completedCycles, tryA)`. If the evaluation finishes:
		 * 		- abruptly, completes with the cause.
		 * 		- normally with `some(tryB)`, completes with `tryB`
		 * 		- normally with `empty`, goes back to the first step.
		 *
		 * $threadSafe
		 *
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that decides if the loop continues or not based on:
		 * 	- the number of already completed cycles,
		 * 	- and the result of the last execution of the `taskA`.
		 * The loop ends when this function returns a [[Maybe.some]]. Its content will be the final result.
		 *
		 * $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return a new [[Task]] that, when executed, repeatedly executes this task and applies the `condition` to the task's result until the function's result is [[Maybe.some]]. The result of this task is the contents of said [[Maybe]] unless any execution of the `taskA` or `condition` terminates abruptly in which case this task result is the cause.
		 * */
		inline final def reiteratedHardyUntilSome[B](maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Maybe[Try[B]]): Task[B] =
			new ReiterateHardyUntilSome(thisTask, maxRecursionDepthPerExecutor)(condition)

		/**
		 * Creates a new [[Task]] that is executed repeatedly until either it fails or applying a condition to: its result and the number of already completed cycles, yields [[Maybe.some]].
		 * ===Detailed description===
		 * Creates a [[Task]] that, when executed, it will:
		 * - execute this task and, if its results is:
		 * 		- `Failure(cause)`, completes with the same failure.
		 * 		- `Success(a)`, applies the `condition` to `(completedCycles, a)`. If the evaluation finishes:	
		 * 			- abruptly, completes with the cause.
		 * 			- normally with `some(tryB)`, completes with `tryB`
		 * 			- normally with `empty`, goes back to the first step.
		 *
		 * $threadSafe
		 *
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that decides if the loop continues or not based on:
		 * 	- the number of already completed cycles,
		 * 	- and the result of the last execution of the `taskA`.
		 * The loop ends when this function returns a [[Maybe.some]]. Its content will be the final result.
		 *
		 * $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return a new [[Task]] that, when executed, repeatedly executes this task and applies the `condition` to the task's result until the function's result is [[Maybe.some]]. The result of this task is the contents of said [[Maybe]] unless any execution of the `taskA` or `condition` terminates abruptly in which case this task result is the cause.
		 * */
		inline final def reiteratedUntilSome[B](maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Maybe[Try[B]]): Task[B] =
			reiteratedHardyUntilSome(maxRecursionDepthPerExecutor) { (completedCycles, tryA) =>
				tryA match {
					case Success(a) => condition(completedCycles, a)
					case f: Failure[A] => Maybe.some(f.castTo[B])
				}
			}


		/** Like [[repeatedHardlyUntilSome]] but the condition is a [[PartialFunction]] instead of a function that returns [[Maybe]]. */
		inline final def reiteratedHardyUntilDefined[B](maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, Try[A]), Try[B]]): Task[B] =
			reiteratedHardyUntilSome(maxRecursionDepthPerExecutor)(Function.untupled(Maybe.liftPartialFunction(pf)))

		/**
		 * Repeats this [[Task]] while the given function returns [[Maybe.empty]].
		 * ===Detailed behavior:===
		 * Returns a [[Task]] that, when executed, it will:
		 *  - Apply the `condition` function to `(n, ts0)` where `n` is the number of already completed evaluations of it (starts with zero).
		 *  - If the evaluation finishes:
		 *  	- Abruptly, completes with the cause.
		 *  	- Normally returning `some(b)`, completes with `b`.
		 *  	- Normally returning `empty`, executes the `taskA` and goes back to the first step replacing `ts0` with the result.
		 *
		 * $threadSafe
		 *
		 * @param ts0 the value passed as second parameter to `condition` the first time it is evaluated.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `ta0` if no cycle has been done yet. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @tparam S a supertype of `A`
		 * @tparam B the type of the result of this task.
		 */
		inline final def reiteratedWhileEmpty[S >: A, B](ts0: Try[S], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[S]) => Maybe[B]): Task[B] =
			new ReiterateHardyWhileEmpty[S, B](thisTask, ts0, maxRecursionDepthPerExecutor: Int)(condition)

		/**
		 * Returns a task that, when executed, repeatedly executes this [[Task]] while a [[PartialFunction]] is undefined.
		 * ===Detailed behavior:===
		 * Returns a [[Task]] that, when executed, it will:
		 *  - Check if the partial function is defined in `(n, ts0)` where `n` is the number of already completed evaluations of it (starts with zero). If it:
		 *		- fails, completes with the cause.
		 *  	- is undefined, executes the `taskA` and goes back to the first step replacing `ts0` with the result.
		 *  	- is defined, evaluates it and if it finishes:
		 *			- abruptly, completes with the cause.
		 *			- normally, completes with the result.
		 *
		 * $threadSafe
		 *
		 * @param ts0 the value passed as second parameter to `condition` the first time it is evaluated.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param pf determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `ta0` if no cycle has been done yet. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @tparam S a supertype of `A`
		 * @tparam B the type of the result of this task.
		 */
		inline final def reiteratedWhileUndefined[S >: A, B](ts0: Try[S], maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, Try[S]), B]): Task[B] = {
			reiteratedWhileEmpty(ts0, maxRecursionDepthPerExecutor)(Function.untupled(Maybe.liftPartialFunction(pf)));
		}

		/**
		 * Wraps this task into another that belongs to other [[Doer]].
		 * Useful to chain [[Task]]'s operations that involve different [[Doer]]s.
		 * ===Detailed behavior===
		 * Returns a task that, when executed, it will execute this task within this [[Doer]]'s $DoSiThEx and complete with the same result.
		 * CAUTION: Avoid closing over the same mutable variable from two transformations applied to Task instances belonging to different [[Doer]]s.
		 * Remember that all routines (e.g., functions, procedures, predicates, and callbacks) provided to [[Task]] methods are executed by the $DoSiThEx of the [[Doer]] that owns the [[Task]] instance on which the method is called.
		 * Therefore, calling [[trigger]] on the returned task will execute the `onComplete` passed to it within the $DoSiThEx of the `otherDoer`.
		 *
		 * $threadSafe
		 *
		 * @param otherDoer the [[Doer]] to which the returned task will belong.
		 * */
		override def onBehalfOf(otherDoer: Doer): otherDoer.Task[A] =
			otherDoer.Task.foreign(thisDoer)(this)

		/** Casts the type-path of this [[Task]] to the received [[Doer]]. Usar con cautela.
		 * Esta operación no hace nada en tiempo de ejecución. Solo engaña al compilador para evitar que chille cuando se opera con [[Task]]s que tienen distinto type-path pero se sabe que corresponden al mismo actor ejecutor.
		 * Se decidió hacer que [[Task]] sea un inner class del actor ejecutor para detectar en tiempo de compilación cuando se usa una instancia fuera de dicho actor.
		 * Usar el type-path checking para detectar en tiempo de compilación cuando una [[Task]] está siendo usada fuera del actor ejecutor es muy valioso, pero tiene un costo: El chequeo de type-path es más estricto de lo necesario para este propósito y, por ende, el compilador reportará errores de tipo en situaciones donde se sabe que el actor ejecutor es el correcto. Esta operación ([[castTypePath()]]) está para tratar esos casos.
		 */
		override def castTypePath[E <: Doer](doer: E): doer.Task[A] = {
			assert(thisDoer eq doer);
			this.asInstanceOf[doer.Task[A]]
		}
	}

	object Task {

		/** Creates a [[Task]] whose execution never ends.
		 *
		 * $threadSafe
		 *
		 * @return the task described in the method description.
		 * */
		val never: Task[Nothing] = Never

		/** Creates a [[Task]] whose result is calculated at the call site even before the task is constructed.  The result of its execution is always the provided value.
		 *
		 * $threadSafe
		 *
		 * @param tryA the value that the returned task will give as result every time it is executed.
		 * @return the task described in the method description.
		 */
		inline final def ready[A](tryA: Try[A]): Task[A] = new Immediate(tryA);

		/** Creates a [[Task]] that always succeeds with a result that is calculated at the call site even before the task is constructed. The result of its execution is always a [[Success]] with the provided value.
		 *
		 * $threadSafe
		 *
		 * @param a the value contained in the [[Success]] that the returned task will give as result every time it is executed.
		 * @return the task described in the method description.
		 */
		inline final def successful[A](a: A): Task[A] = new Immediate(Success(a));

		/** Creates a [[Task]] that always fails with a result that is calculated at the call site even before the task is constructed. The result of its execution is always a [[Failure]] with the provided [[Throwable]]
		 *
		 * $threadSafe
		 *
		 * @param throwable the exception contained in the [[Failure]] that the returned task will give as result every time it is executed.
		 * @return the task described in the method description.
		 */
		inline final def failed[A](throwable: Throwable): Task[A] = new Immediate(Failure(throwable));

		/**
		 * Creates a task whose result is calculated withing the $DoSiThEx.
		 * ===Detailed behavior===
		 * Creates a task that, when executed, evaluates the `resultSupplier` within the $DoSiThEx. If the evaluation finishes:
		 *		- abruptly, completes with a [[Failure]] with the cause.
		 *		- normally, completes with the evaluation's result.
		 *
		 * $$threadSafe
		 *
		 * @param supplier the supplier of the result. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return the task described in the method description.
		 */
		inline final def own[A](supplier: () => Try[A]): Task[A] = new Own(supplier);

		/**
		 * Creates a task whose result is calculated withing the $DoSiThEx and completes successfully as long as the evaluation of the supplier finishes normally.
		 * ===Detailed behavior===
		 * Creates a task that, when executed, evaluates the `resultSupplier` within the $DoSiThEx. If it finishes:
		 *		- abruptly, completes with a [[Failure]] containing the cause.
		 *		- normally, completes with a [[Success]] containing the evaluation's result.
		 *
		 * Is equivalent to {{{ own { () => Success(resultSupplier()) } }}}
		 *
		 * $threadSafe
		 *
		 * @param supplier La acción que estará encapsulada en la Task creada. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return the task described in the method description.
		 */
		inline final def mine[A](supplier: () => A): Task[A] = new Own(() => Success(supplier()));

		/** Create a [[Task]] that just waits the completion of the specified [[Future]]. The result of the task, when executed, is the result of the received [[Future]].
		 *
		 * $threadSafe
		 *
		 * @param future the future to wait for.
		 * @return the task described in the method description.
		 */
		inline final def wait[A](future: Future[A]): Task[A] = new Wait(future);

		/** Creates a [[Task]] that, when executed, triggers the execution of a process in an alien executor, and waits its result, successful or not.
		 * The process executor is usually foreign but may be the $DoSiThEx.
		 *
		 * $threadSafe
		 *
		 * @param supplier a function that starts the process and return a [[Future]] of its result. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return the task described in the method description.
		 */
		inline final def alien[A](supplier: () => Future[A]): Task[A] = new Alien(supplier);

		/** Creates a [[Task]] that, when executed, triggers the execution of a task that belongs to another [[Doer]] within that [[Doer]]'s $DoSiThEx.
		 * $threadSafe
		 *
		 * @param foreignDoer the [[Doer]] to whom the `foreignTask` belongs.
		 * @return a task that belongs to this [[Doer]] and completes when the `foreignTask` is completed by the `foreignDoer`. */
		inline final def foreign[A](foreignDoer: Doer)(foreignTask: foreignDoer.Task[A]): Task[A] = {
			if foreignDoer eq thisDoer then foreignTask.asInstanceOf[thisDoer.Task[A]]
			else new Foreign(foreignDoer)(foreignTask)
		}

		/**
		 * Creates a [[Task]] that simultaneously triggers the execution of two tasks and returns their results combined with the received function.
		 * Given the single thread nature of [[Doer]] this operation only has sense when the received tasks are a chain of actions that involve timers, foreign, or alien tasks.
		 * ===Detailed behavior===
		 * Creates a new [[Task]] that, when executed:
		 *		- triggers the execution of both: `taskA` and `taskB`
		 *		- when both are completed, whether normal or abruptly, the function `f` is applied to their results and if the evaluation finishes:
		 *			- abruptly, completes with a [[Failure]] containing the cause.
		 *			- normally, completes with the evaluation's result.
		 *
		 * $threadSafe
		 *
		 * @param taskA a task
		 * @param taskB a task
		 * @param f the function that combines the results of the `taskA` and `taskB`. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return the task described in the method description.
		 */
		inline final def combine[A, B, C](taskA: Task[A], taskB: Task[B])(f: (Try[A], Try[B]) => Try[C]): Task[C] =
			new CombinedTask(taskA, taskB, f)

		/**
		 * Creates a task that, when executed, simultaneously triggers the execution of all the [[Task]]s in the received list, and completes with a list containing their results in the same order.
		 *
		 * $threadSafe
		 *
		 * @param tasks the list of [[Task]]s that the returned task will trigger simultaneously to combine their results.
		 * @return the task described in the method description.
		 *
		 * */
		final def sequence[A](tasks: List[Task[A]]): Task[List[A]] = {
			@tailrec
			def loop(incompleteResult: Task[List[A]], remainingTasks: List[Task[A]]): Task[List[A]] = {
				remainingTasks match {
					case Nil =>
						incompleteResult
					case head :: tail =>
						val lessIncompleteResult = combine(incompleteResult, head) { (tla, ta) =>
							for {
								la <- tla
								a <- ta
							} yield a :: la
						}
						loop(lessIncompleteResult, tail)
				}
			}

			tasks.reverse match {
				case Nil => successful(Nil)
				case lastTask :: previousTasks => loop(lastTask.map(List(_)), previousTasks);
			}
		}

		/** Creates a new [[Task]] that, when executed, repeatedly constructs a task and executes it while a condition returns [[Right]].
		 * ==Detailed behavior:==
		 * Gives a new [[Task]] that, when executed, it will:
		 *  - Apply the function `condition` to `(completedCycles, tryA0)`, and if it finishes:
		 *  	- Abruptly, completes with the cause.
		 *		- Normally, returning a `Left(tryB)`, completes with `tryB`.
		 *  	- Normally, returning a `Right(taskA)`, executes the `taskA` and goes back to the first step, replacing `tryA0` with the result.
		 *
		 * $threadSafe
		 *
		 * @param tryA0 the initial iteration state.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that, based on the `completedExecutionsCounter` and the iteration's state `tryA`, determines if the loop should end or otherwise creates the [[Task]] to execute in the next iteration.
		 * @tparam A the type of the state passed from an iteration to the next.
		 * @tparam B the type of the result of created [[Task]]
		 */
		inline final def whileRightReiterateHardy[A, B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Either[Try[B], Task[A]]): Task[B] =
			new WhileRightReiterateHardy[A, B](tryA0, maxRecursionDepthPerExecutor)(condition);

		/** Creates a new [[Task]] that, when executed, repeatedly constructs a task and executes it as long as the `condition` is met.
		 * ==Detailed behavior:==
		 * Gives a new [[Task]] that, when executed, it will:
		 *  - Apply the function `condition` to `(completedCycles, a0)`, and if it finishes:
		 *  	- Abruptly, completes with the cause.
		 *		- Normally, returning a `Left(tryB)`, completes with `tryB`.
		 *  	- Normally, returning a `Right(taskA)`, executes the `taskA`, and if it terminates with:
		 *  		- Failure(cause), completes with the cause.
		 *  		- Success(a1), goes back to the first step, replacing `a0` with `a1`.
		 *
		 * $threadSafe
		 *
		 * @param a0 the initial iteration state.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that, based on the `completedExecutionsCounter` and the iteration's state `a`, determines if the loop should end or otherwise creates the [[Task]] to execute in the next iteration.
		 * @tparam A the type of the state passed from an iteration to the next.
		 * @tparam B the type of the result of created [[Task]]
		 */
		final def whileRightReiterate[A, B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Either[Try[B], Task[A]]): Task[B] = {
			whileRightReiterateHardy[A, B](Success(a0), maxRecursionDepthPerExecutor) { (completedCycles, tryA) =>
				tryA match {
					case Success(a) => condition(completedCycles, a)
					case Failure(cause) => Left(Failure(cause))
				}
			}
		}

		@deprecated("lo hice como ejercicio")
		private def reiterateUntilLeft2[A, B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Task[Either[Try[B], A]]): Task[B] =
			whileRightReiterateHardy[Either[Try[B], A], B](tryA0.map(Right(_)), maxRecursionDepthPerExecutor)((completedExecutionsCounter, previousState) =>
				previousState match {
					case Success(Right(x)) => Right(condition(completedExecutionsCounter, Success(x)))
					case Success(Left(x)) => Left(x)
					case Failure(cause) => Right(condition(completedExecutionsCounter, Failure(cause)))
				}
			)

		/** Creates a new [[Task]] that, when executed, repeatedly constructs and executes tasks as long as the `condition` is met.
		 * ===Detailed behavior:===
		 * Gives a new [[Task]] that, when executed, it will:
		 * - Try to apply the function `condition` to `(n,a0)` where n is the number of cycles already done. If the evaluation completes:
		 *		- Abruptly, completes with the cause.
		 *		- Normally, returning a `task`, executes the `task` and if its result is:
		 *			- `Failure(cause)`, completes with that `Failure(cause)`.
		 *			- `Success(Left(tryB))`, completes with `tryB`.
		 *			- `Success(Right(a1))`, goes back to the first step replacing `a0` with `a1`.
		 *
		 * $threadSafe
		 * @param a0 the initial iteration state.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that, based on the `completedExecutionsCounter` and the iteration's state (which starts with `a0`), determines if the loop should end or otherwise creates the [[Duty]] to execute in the next iteration.
		 * @tparam A the type of the state passed from an iteration to the next.
		 * @tparam B the type of the result of created [[Duty]]
		 * */
		inline final def reiterateUntilLeft[A, B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Task[Either[Try[B], A]]): Task[B] =
			new ReiterateUntilLeft(a0, maxRecursionDepthPerExecutor)(condition)


		/** Creates a new [[Task]] that, when executed, repeatedly constructs and executes tasks until it succeed or the `maxRetries` is reached.
		 * ===Detailed behavior:===
		 * When the returned [[Task]] is executed, it will:
		 *		- Try to apply the function `taskBuilder` to the number of tries that were already done. If the evaluation completes:
		 *  		- Abruptly, completes with the cause.
		 *  		- Normally, executes the returned task and if the result is:
		 *				- `Failure(cause)`, completes with `Failure(cause)`.
		 *				- `Success(Right(b))`, completes with `Success(b)`.
		 *				- `Success(Left(a))`, compares the retries counter against `maxRetries` and if:
		 *					- `retriesCounter >= maxRetries`, completes with `Left(a)`
		 *					- `retriesCounter < maxRetries`, increments the `retriesCounter` (which starts at zero) and goes back to the first step.
		 *
		 * $threadSafe
		 * @param maxRetries the maximum number of retries. Note that N retries is equivalent to N+1 attempts. So, a value of zero retries is one attempt.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param taskBuilder function to construct tasks, taking the retry count as input.
		 * */
		inline final def attemptUntilRight[A, B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Task[Either[A, B]]): Task[Either[A, B]] =
			new AttemptUntilRight[A, B](maxRetries, maxRecursionDepthPerExecutor)(taskBuilder)

		@deprecated("No se usa. Lo hice como ejercicio")
		private def attemptUntilRight2[A, B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Task[Either[A, B]]): Task[Either[A, B]] =
			reiterateUntilLeft[Null, Either[A, B]](null, maxRecursionDepthPerExecutor) { (triesCounter, _) =>
				taskBuilder(triesCounter).map {
					case rb@Right(b) => Left(Success(rb))
					case Left(a) => Right(null)
				}
			}


		/** Returns a new [[Task]] that, when executed:
		 * 	- creates and executes a control task and, depending on its result, either:
		 *		- completes.
		 *		- or creates and executes an interleaved task and then goes back to the first step.
		 * WARNING: the execution of the returned task will never end if the control task always returns [[Right]].
		 *
		 * $threadSafe
		 *
		 * @param a0 2nd argument passed to `controlTaskBuilder` in the first cycle.
		 * @param b0 3rd argument passed to `controlTaskBuilder` in the first cycle.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param controlTaskBuilder the function that builds the control tasks. It takes three parameters:
		 * - the number of already executed interleaved tasks.
		 * - the result of the control task in the previous cycle or `a0` in the first cycle.
		 * - the result of the interleaved task in the previous cycle or `b0` in the first cycle.
		 * @param interleavedTaskBuilder the function that builds the interleaved tasks. It takes two parameters:
		 * - the number of already executed interleaved tasks.
		 *  - the result of the control task in the current cycle.
		 * */
		final def reiterateInterleavedUntilLeft[A, B, R](a0: A, b0: B, maxRecursionDepthPerExecutor: Int = 9)(controlTaskBuilder: (Int, A, B) => Task[Either[Try[R], A]])(interleavedTaskBuilder: (Int, A) => Task[B]): Task[R] = {

			reiterateUntilLeft[(A, B), R]((a0, b0), maxRecursionDepthPerExecutor) { (completedCycles, ab) =>
				controlTaskBuilder(completedCycles, ab._1, ab._2).flatMap {
					case Left(tryR) =>
						Task.successful(Left(tryR))

					case Right(a) =>
						interleavedTaskBuilder(completedCycles, a).map(b => Right((a, b)))

				}
			}
		}
	}

	/** A [[Task]] that never completes.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 * */
	object Never extends Task[Nothing] {
		override def engage(onComplete: Try[Nothing] => Unit): Unit = ()
	}

	/**
	 * @param taskA the [[Task]] from where this duty takes its result.
	 * @param exceptionHandler converts the faulty results of the `taskA` to instances of `B`.
	 *
	 * $notGuarded */
	final class ToDuty[A, B >: A](taskA: Task[A], exceptionHandler: Throwable => B) extends Duty[B] {
		override def engage(onComplete: B => Unit): Unit = {
			taskA.engagePortal { tryA =>
				val b = tryA match {
					case Success(a) => a
					case Failure(exception) => exceptionHandler(exception)
				}
				onComplete(b)
			}
		}
		override def toString: String = deriveToString[ToDuty[A, B]](this)
	}


	/** A [[Task]] whose result is calculated at the call site even before the task is constructed. The result of its execution is always the provided value.
	 *
	 * $threadSafe
	 *
	 * @param tryA the value that the returned task will give as result every time it is executed.
	 * @return the task described in the method description.
	 * $onCompleteExecutedByDoSiThEx
	 */
	final class Immediate[+A](tryA: Try[A]) extends Task[A] {
		override def engage(onComplete: Try[A] => Unit): Unit =
			onComplete(tryA)

		override def toFuture(isRunningInDoSiThEx: Boolean): Future[A] =
			Future.fromTry(tryA)

		override def toFutureHardy(isRunningInDoSiThEx: Boolean = false): Future[Try[A]] =
			Future.successful(tryA)

		override def toString: String = deriveToString[Immediate[A]](this)
	}

	/** A [[Task]] that, when executed, calls the `resultSupplier` within the $DoSiThEx and if it finishes:
	 *  - abruptly, completes with the cause.
	 *  - normally, completes with the result.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param resultSupplier builds the result of this [[Task]]. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
	 */
	final class Own[+A](resultSupplier: () => Try[A]) extends Task[A] {
		override def engage(onComplete: Try[A] => Unit): Unit = {
			val result =
				try resultSupplier()
				catch {
					case NonFatal(e) => Failure(e)
				}
			onComplete(result)
		}

		override def toString: String = deriveToString[Own[A]](this)
	}

	/** A [[Task]] that just waits the completion of the specified [[Future]]. The result of the task, when executed, is the result of the received [[Future]].
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param resultFuture the future to wait for.
	 */
	final class Wait[+A](resultFuture: Future[A]) extends Task[A] {
		override def engage(onComplete: Try[A] => Unit): Unit =
			resultFuture.onComplete(onComplete)(ownSingleThreadExecutionContext)

		override def toString: String = deriveToString[Wait[A]](this)
	}

	/** A [[Task]] that, when executed, triggers the execution of a process in an alien executor and waits its result, successful or not.
	 * The process executor may be the $DoSiThEx but if that is the intention [[Own]] would be more appropriate.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param futureBuilder a function that starts the process and return a [[Future]] of its result. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
	 * */
	final class Alien[+A](futureBuilder: () => Future[A]) extends Task[A] {
		override def engage(onComplete: Try[A] => Unit): Unit = {
			val future =
				try futureBuilder()
				catch {
					case NonFatal(e) => Future.failed(e)
				}
			future.onComplete(onComplete)(ownSingleThreadExecutionContext)
		}

		override def toString: String = deriveToString[Alien[A]](this)
	}

	final class Foreign[+A](foreignDoer: Doer)(foreignTask: foreignDoer.Task[A]) extends Task[A] {
		override def engage(onComplete: Try[A] => Unit): Unit = {
			foreignTask.trigger() { tryA => queueForSequentialExecution(onComplete(tryA)) }
		}

		override def toString: String = deriveToString[Foreign[A]](this)
	}

	/**
	 * A [[Task]] that consumes the results of the received task for its side effects.
	 * ===Detailed behavior===
	 * A [[Task]] that, when executed, it will:
	 *		- trigger the execution of `taskA` and tries to apply the `consumer` to its result. If the evaluation finishes:
	 *			- abruptly, completes with a [[Failure]] containing the cause.
	 *			- normally, completes with `Success(())`.
	 */
	final class Consume[A](taskA: Task[A], consumer: Try[A] => Unit) extends Task[Unit] {
		override def engage(onComplete: Try[Unit] => Unit): Unit = {
			taskA.engagePortal { tryA =>
				val tryConsumerResult =
					try Success(consumer(tryA))
					catch {
						case NonFatal(cause) => Failure(cause)
					}
				onComplete(tryConsumerResult)
			}
		}

		override def toString: String = deriveToString[Consume[A]](this)
	}

	/**
	 * A [[Task]] that transforms the received task by converting successful results to `Failure(new NoSuchElementException())` if it does not satisfy the received `predicate`.
	 * Needed to support filtering and case matching in for-compressions. The for-expressions (or for-bindings) after the filter are not executed if the [[predicate]] is not satisfied.
	 * ===Detailed behavior===
	 * A [[Task]] that, when executed, it will:
	 *		- trigger the execution of `taskA` and, if the result is a:
	 *			- [[Failure]], completes with that failure.
	 *			- [[Success]], tries to apply the `predicate` to its content. If the evaluation finishes:
	 *				- abruptly, completes with the cause.
	 *				- normally with a `false`, completes with a [[Failure]] containing a [[NoSuchElementException]].
	 *				- normally with a `true`, completes with the result of this task.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * */
	final class WithFilter[A](taskA: Task[A], predicate: A => Boolean) extends Task[A] {
		override def engage(onComplete: Try[A] => Unit): Unit = {
			taskA.engagePortal {
				case sa@Success(a) =>
					val predicateResult =
						try {
							if predicate(a) then sa
							else Failure(new NoSuchElementException(s"Task filter predicate is not satisfied for $a"))
						} catch {
							case NonFatal(cause) =>
								Failure(cause)
						}
					onComplete(predicateResult)

				case f@Failure(_) =>
					onComplete(f)
			}
		}

		override def toString: String = deriveToString[WithFilter[A]](this)
	}


	/**
	 * A [[Task]] that transform the result of another task.
	 * ===Detailed description===
	 * A [[Task]] that, when executed, triggers the execution of the `originalTask` and applies the received `resultTransformer` to its results. If the evaluation finishes:
	 *		- abruptly, completes with the cause.
	 *		- normally, completes with the result of the evaluation.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param resultTransformer applied to the result of the `originalTask` to obtain the result of this task.
	 *
	 * $isExecutedByDoSiThEx
	 *
	 * $unhandledErrorsArePropagatedToTaskResult
	 */
	final class Transform[+A, +B](originalTask: Task[A], resultTransformer: Try[A] => Try[B]) extends Task[B] {
		override def engage(onComplete: Try[B] => Unit): Unit = {
			originalTask.engagePortal { tryA =>
				val tryB =
					try resultTransformer(tryA)
					catch {
						case NonFatal(e) => Failure(e);
					}
				onComplete(tryB)
			};
		}

		override def toString: String = deriveToString[Transform[A, B]](this)
	}

	/**
	 * A [[Task]] that composes two [[Task]]s where the second is build from the result of the first.
	 * ===Detailed behavior===
	 * A [[Task]] which, when executed, it will:
	 *		- Trigger the execution of `taskA` and apply the `taskBBuilder` function to its result. If the evaluation finishes:
	 *			- abruptly, completes with the cause.
	 *			- normally with `taskB`, triggers the execution of `taskB` and completes with its result.
	 *
	 * $threadSafe
	 *
	 * @param taskA the task that is executed first.
	 * @param taskBBuilder a function that receives the result of the execution of `taskA` and builds the task to be executed next. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
	 */
	final class Compose[+A, +B](taskA: Task[A], taskBBuilder: Try[A] => Task[B]) extends Task[B] {
		override def engage(onComplete: Try[B] => Unit): Unit = {
			taskA.engagePortal { tryA =>
				val taskB =
					try taskBBuilder(tryA)
					catch {
						case NonFatal(e) => Task.failed(e)
					}
				taskB.engagePortal(onComplete)
			}
		}

		override def toString: String = deriveToString[Compose[A, B]](this)
	}


	/** A [[Task]] that, when executed:
	 *		- triggers the execution of both: `taskA` and `taskB`;
	 *		- when both are completed, whether normal or abruptly, the function `f` is applied to their results and if the evaluation finishes:
	 *			- abruptly, completes with the cause.
	 *			- normally, completes with the result.
	 *
	 * $threadSafe
	 * $onCompleteExecutedByDoSiThEx
	 */
	final class CombinedTask[+A, +B, +C](taskA: Task[A], taskB: Task[B], f: (Try[A], Try[B]) => Try[C]) extends Task[C] {
		override def engage(onComplete: Try[C] => Unit): Unit = {
			var ota: Maybe[Try[A]] = Maybe.empty;
			var otb: Maybe[Try[B]] = Maybe.empty;
			taskA.engagePortal { tryA =>
				otb.fold { ota = Maybe.some(tryA) } { tryB =>
					val tryC =
						try f(tryA, tryB)
						catch {
							case NonFatal(e) => Failure(e)
						}
					onComplete(tryC)
				}
			}
			taskB.engagePortal { tryB =>
				ota.fold { otb = Maybe.some(tryB) } { tryA =>
					val tryC =
						try f(tryA, tryB)
						catch {
							case NonFatal(e) => Failure(e)
						}
					onComplete(tryC)
				}
			}
		}

		override def toString: String = deriveToString[CombinedTask[A, B, C]](this)
	}


	/**
	 * A [[Task]] that executes the received task until applying the received function yields [[Maybe.some]].
	 * ===Detailed description===
	 * A [[Task]] that, when executed, it will:
	 *		- execute the `taskA` producing the result `tryA`
	 *		- apply `condition` to `(completedCycles, tryA)`. If the evaluation finishes:
	 *			- abruptly, completes with the cause.
	 *			- normally with `some(tryB)`, completes with `tryB`
	 *			- normally with `empty`, goes back to the first step.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param taskA the task to be repeated.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param condition function that decides if the loop continues or not based on:
	 *		- the number of already completed cycles,
	 *		- and the result of the last execution of the `taskA`.
	 * The loop ends when this function returns a [[Maybe.some]]. Its content will be the final result of this task.
	 *
	 * $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
	 * */
	final class ReiterateHardyUntilSome[+A, +B](taskA: Task[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Maybe[Try[B]]) extends Task[B] {

		override def engage(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param completedCycles number of already completed cycles.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case more synchronous scenario. */
			def loop(completedCycles: Int, recursionDepth: Int): Unit = {
				taskA.engagePortal { tryA =>
					val conditionResult: Maybe[Try[B]] =
						try condition(completedCycles, tryA)
						catch {
							case NonFatal(cause) => Maybe.some(Failure(cause))
						}
					conditionResult.fold {
						if (recursionDepth < maxRecursionDepthPerExecutor) {
							loop(completedCycles + 1, recursionDepth + 1)
						} else {
							queueForSequentialExecution(loop(completedCycles + 1, 0))
						}
					} (onComplete)
				}
			}

			loop(0, 0)
		}

		override def toString: String = deriveToString[ReiterateHardyUntilSome[A, B]](this)
	}

	/**
	 * Task that, when executed, repeatedly executes a task while a condition return [[Maybe.empty]].
	 * ===Detailed behavior:===
	 * When this [[Task]] is executed, it will:
	 *  - Try to apply the `condition` function to `(n, ta0)` where `n` is the number of already completed evaluations of it.
	 *  - If the evaluation completes:
	 *  	- Abruptly, completes with the cause.
	 *  	- Normally, returning `some(b)`, completes with `b`.
	 *  	- Normally, returning `empty`, executes the `taskA` and goes back to the first step replacing `ta0` with the result.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param taskA the task to be repeated
	 * @param ta0 the value passed as second parameter `condition` the first time it is evaluated.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param condition determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `ta0` if no cycle has been done yet. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
	 * @tparam A the type of the result of the repeated task `taskA`.
	 * @tparam B the type of the result of this task.
	 */
	final class ReiterateHardyWhileEmpty[+A, +B](taskA: Task[A], ta0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Maybe[B]) extends Task[B] {
		override def engage(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param completedCycles number of already completed cycles.
			 * @param lastTaskResult the result of the last task execution.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case more synchronous scenario. */
			def loop(completedCycles: Int, lastTaskResult: Try[A], recursionDepth: Int): Unit = {
				val conditionResult: Maybe[Try[B]] =
					try {
						condition(completedCycles, lastTaskResult)
							.fold(Maybe.empty)(b => Maybe.some(Success(b)))
					}
					catch {
						case NonFatal(cause) => Maybe.some(Failure(cause))
					}

				conditionResult.fold {
					taskA.engagePortal { newTryA =>
						if recursionDepth < maxRecursionDepthPerExecutor then loop(completedCycles + 1, newTryA, recursionDepth + 1)
						else queueForSequentialExecution(loop(completedCycles + 1, newTryA, 0))
					}
				}(onComplete)
			}

			loop(0, ta0, 0)
		}

		override def toString: String = deriveToString[ReiterateHardyWhileEmpty[A, B]](this)
	}


	/** Task that, when executed, repeatedly constructs and executes tasks as long as the `condition` is met.
	 * ===Detailed behavior:===
	 * When this [[Task]] is executed, it will:
	 *  - Try to apply the function `checkAndBuild` to `(n, tryA0)` where `n` is the number of already completed cycles, and if it completes:
	 *  	- Abruptly, completes with the cause.
	 *		- Normally, returning a `Left(tryB)`, completes with `tryB`.
	 *  	- Normally, returning a `Right(taskA)`, executes the `taskA` and goes back to the first step replacing `tryA0` with the result.
	 *
	 * @param tryA0 the initial value wrapped in a `Try`, used in the first call to `checkAndBuild`.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param checkAndBuild a function that takes the number of already completed cycles and the last task result wrapped in a `Try`, returning an `Either[Try[B], Task[A]]` indicating the next action to perform.	$isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult *
	 */
	final class WhileRightReiterateHardy[+A, +B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(checkAndBuild: (Int, Try[A]) => Either[Try[B], Task[A]]) extends Task[B] {
		override def engage(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param completedCycles number of already completed cycles, which consist of a task creation and its execution.
			 * @param lastTaskResult the result of the last task execution.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(completedCycles: Int, lastTaskResult: Try[A], recursionDepth: Int): Unit = {
				val tryBOrTaskA =
					try checkAndBuild(completedCycles, lastTaskResult)
					catch {
						case NonFatal(cause) => Left(Failure(cause));
					}
				tryBOrTaskA match {
					case Left(tryB) =>
						onComplete(tryB);
					case Right(taskA) =>
						taskA.engagePortal { newTryA =>
							if (recursionDepth < maxRecursionDepthPerExecutor) loop(completedCycles + 1, newTryA, recursionDepth + 1)
							else queueForSequentialExecution(loop(completedCycles + 1, newTryA, 0));
						}
				}
			}

			loop(0, tryA0, 0)
		}

		override def toString: String = deriveToString[WhileRightReiterateHardy[A, B]](this)
	}

	/** Task that, when executed, repeatedly constructs and executes tasks until the result is [[Left]] or failed.
	 * ===Detailed behavior:===
	 * When this [[Task]] is executed, it will:
	 *  - Try to apply the function `buildAndCheck` to `(n, a0)` where n is the number of already completed cycles. If the evaluation completes:
	 *  	- Abruptly, completes with the cause.
	 *  		- Normally, returning a `task`, executes the `task` and if its result is:
	 *  			- `Failure(cause)`, completes with that `Failure(cause)`.
	 *  			- `Success(Left(tryB))`, completes with `tryB`.
	 *  			- `Success(Right(a1))`, goes back to the first step replacing `a0` with `a1`.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param a0 the initial value used in the first call to `buildAndCheck`.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param buildAndCheck a function that takes the number of already completed cycles and the last task result, returning a new task that yields an `Either[Try[B], A]` indicating the next action to perform. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
	 */
	final class ReiterateUntilLeft[+A, +B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(buildAndCheck: (Int, A) => Task[Either[Try[B], A]]) extends Task[B] {
		override def engage(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param executionsCounter number of already completed cycles, which consist of a task creation and its execution.
			 * @param lastTaskResult the result of the last task execution.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(executionsCounter: Int, lastTaskResult: A, recursionDepth: Int): Unit = {
				val task: Task[Either[Try[B], A]] =
					try buildAndCheck(executionsCounter, lastTaskResult)
					catch {
						case NonFatal(e) => Task.successful(Left(Failure(e)))
					}
				task.engagePortal {
					case Success(Right(a)) =>
						if (recursionDepth < maxRecursionDepthPerExecutor) loop(executionsCounter + 1, a, recursionDepth + 1)
						else queueForSequentialExecution(loop(executionsCounter + 1, a, 0));
					case Success(Left(tryB)) =>
						onComplete(tryB)
					case Failure(e) =>
						onComplete(Failure(e))
				}
			}

			loop(0, a0, 0)
		}

		override def toString: String = deriveToString[ReiterateUntilLeft[A, B]](this)
	}

	/** Task that, when executed, repeatedly constructs and executes tasks until the result is [[Right]] or the `maxRetries` is reached.
	 * ===Detailed behavior:===
	 * When it is executed, it will:
	 *  - Try to apply the function `taskBuilder` to the number of tries that were already done. If the evaluation completes:
	 *  	- Abruptly, completes with the cause.
	 *  		- Normally, executes the returned task and if the result is:
	 *  			- `Failure(cause)`, completes with `Failure(cause)`.
	 *  			- `Success(Right(b))`, completes with `Success(b)`.
	 *  			- `Success(Left(a))`, compares the retries counter against `maxRetries` and if:
	 *  				- `retriesCounter >= maxRetries`, completes with `Left(a)`
	 *  				- `retriesCounter < maxRetries`, increments the `retriesCounter` (which starts at zero) and goes back to the first step.
	 */
	final class AttemptUntilRight[+A, +B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Task[Either[A, B]]) extends Task[Either[A, B]] {
		override def engage(onComplete: Try[Either[A, B]] => Unit): Unit = {
			/**
			 * @param attemptsAlreadyMade the number attempts already made.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(attemptsAlreadyMade: Int, recursionDepth: Int): Unit = {
				val task: Task[Either[A, B]] =
					try taskBuilder(attemptsAlreadyMade)
					catch {
						case NonFatal(cause) => Task.failed(cause)
					}
				task.engagePortal {
					case success@Success(aOrB) =>
						aOrB match {
							case _: Right[A, B] =>
								onComplete(success)
							case Left(a) =>
								if (attemptsAlreadyMade >= maxRetries) {
									onComplete(success);
								} else if (recursionDepth < maxRecursionDepthPerExecutor) {
									loop(attemptsAlreadyMade + 1, recursionDepth + 1);
								} else {
									queueForSequentialExecution(loop(attemptsAlreadyMade + 1, 0))
								}
						}
					case failure: Failure[Either[A, B]] =>
						onComplete(failure);
				}
			}

			loop(0, 0)
		}

		override def toString: String = deriveToString[AttemptUntilRight[A, B]](this)
	}

	////////////// COMMITMENT ///////////////

	/** A commitment to complete a [[Task]].
	 * Analogous to [[scala.concurrent.Promise]] but for a [[Task]] instead of a [[scala.concurrent.Future]].
	 * */
	final class Commitment[A] { thisCommitment =>
		private var oResult: Maybe[Try[A]] = Maybe.empty;
		private var onCompletedObservers: List[Try[A] => Unit] = Nil;

		/** @return true if this [[Commitment]] was either fulfilled or broken; or false if it is still pending. */
		def isCompleted: Boolean = this.oResult.isDefined;

		/** @return true if this [[Commitment]] is still pending; or false if it was completed. */
		def isPending: Boolean = this.oResult.isEmpty;

		/** The [[Task]] this [[Commitment]] promises to complete. This task's will complete when this [[Commitment]] is fulfilled or broken. That could be done immediately calling [[fulfill]], [[break]], [[complete]], or in a deferred manner by calling [[completeWith]]. */
		val task: Task[A] = (onComplete: Try[A] => Unit) => {
			thisCommitment.oResult.fold {
				thisCommitment.onCompletedObservers = onComplete :: thisCommitment.onCompletedObservers
			}(onComplete)
		}

		/** Provokes that the [[task]] that this [[Commitment]] promises to complete to be completed with the received `result`.
		 *
		 * @param result the result that the [[task]] this [[Commitment]] promised to complete . */
		def complete(result: Try[A])(onAlreadyCompleted: Try[A] => Unit = _ => ()): this.type = {
			queueForSequentialExecution {
				oResult.fold {
					this.oResult = Maybe.some(result);
					this.onCompletedObservers.foreach(_(result));
					// la lista de observadores quedó obsoleta. Borrarla para minimizar posibilidad de memory leak.
					this.onCompletedObservers = Nil
				} { value =>
					try onAlreadyCompleted(value)
					catch {
						case NonFatal(cause) => reportFailure(cause)
					}

				}
			};
			this;
		}

		/** Provokes that the [[task]] this [[Commitment]] promises to complete to be fulfilled (completed successfully) with the received `result`. */
		def fulfill(result: A)(onAlreadyCompleted: Try[A] => Unit = _ => ()): this.type = this.complete(Success(result))(onAlreadyCompleted);

		/** Provokes that the [[task]] this [[Commitment]] promises to complete to be broken (completed with failure) with the received `cause`. */
		def break(cause: Throwable)(onAlreadyCompleted: Try[A] => Unit = _ => ()): this.type = this.complete(Failure(cause))(onAlreadyCompleted);

		/** Programs the completion of the [[task]] this [[Commitment]] promises to complete to be completed with the result of the received [[Task]] when it is completed. */
		def completeWith(otherTask: Task[A])(onAlreadyCompleted: Try[A] => Unit = _ => ()): this.type = {
			if (otherTask ne this.task)
				otherTask.trigger()(result => complete(result)(onAlreadyCompleted));
			this
		}
	}

	//////////////// Flow //////////////////////

	object Flow {
		def lift[A, B](f: A => B): Flow[A, B] =
			(a: A) => Duty.ready(f(a))

		def wrap[A, B](builder: A => Duty[B]): Flow[A, B] =
			(a: A) => builder(a)
	}

	trait Flow[A, B] { thisFlow =>

		protected def flush(a: A): Duty[B]

		inline def apply(a: A, inline isRunningInDoSiThEx: Boolean = false)(onComplete: B => Unit): Unit = {
			def work(): Unit = flush(a).engagePortal(onComplete)

			if isRunningInDoSiThEx then work()
			else queueForSequentialExecution(work())
		}

		/** Connects this flow output with the input of the received one. */
		def to[C](next: Flow[B, C]): Flow[A, C] =
			(a: A) => thisFlow.flush(a).flatMap(b => next.flush(b))

		/** Connects the received flow output with the input of this one. */
		def from[Z](previous: Flow[Z, A]): Flow[Z, B] =
			(z: Z) => previous.flush(z).flatMap(a => thisFlow.flush(a))
	}
}