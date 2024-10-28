package readren.taskflow

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

object TaskDomain {
	/** Specifies what instances of [[TaskDomain]] require to function properly. */
	trait Assistant {
		/**
		 * The implementation should queue the execution of all the [[Runnable]]s this method receives on the same single-thread executor. Note that "single" does not imply "same". The thread may change.
		 * From now on said executor will be called "the domain's single-thread executor" or DoSiThEx for short.
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
 * All the deferred actions preformed by the operations of the [[Task]]s enclosed by this [[TaskDomain]] are executed by calling the [[assistant.queueForSequentialExecution]] method unless the method documentation says otherwise. That includes not only the call-back functions like `onComplete` but also all the functions, procedures, predicates, and by-name parameters they receive.
 * ==Note:==
 * At the time of writing, almost all the operations and classes in this source file are thread-safe and may function properly on any kind of execution context. The only exceptions are the classes [[Combine]] and [[Commitment]], which could be enhanced to support concurrency. However, given that a design goal was to allow [[Task]] and the functions their operators receive to close over variables in code sections guaranteed to be executed solely by the DoSiThEx (domain's single-threaded executor), the effort and cost of making them concurrent would be unnecessary.
 * See [[TaskDomain.Assistant.queueForSequentialExecution()]].
 *
 * @define DoSiThEx DoSiThEx (domain's single-thread executor)
 * @define onCompleteExecutedByDoSiThEx El call-back `onComplete` pasado a `efectuar/ejecutar` es siempre, sin excepción, ejecutado por este actor sin envoltura `try..catch`. Esto es parte del contrato de este trait.
 * @define threadSafe This method is thread-safe.
 * @define isExecutedByDoSiThEx Executed within the DoSiThEx (domain's single-thread executor).
 * @define unhandledErrorsArePropagatedToTaskResult The call to this function is guarded with try-catch. If it throws a non-fatal exception it will be caught and the [[Task]] will complete with a [[Failure]] containing the error.
 * @define unhandledErrorsAreReported The call to this function is guarded with a try-catch. If it throws a non-fatal exception it will be caught and reported with [[TaskDomain.Assistant.reportFailure()]].
 * @define isNotGuarded This function call is not guarded with try-catch. Only transformations that meant to be executed in the DoSiThEx are guarded.
 * @define maxRecursionDepthPerExecutor Maximum recursion depth per executor. Once this limit is reached, the recursion continues in a new executor. The result does not depend on this parameter as long as no [[StackOverflowError]] occurs.
 * @define isRunningInDoSiThEx indicates whether the caller is certain that this method is being executed within the $DoSiThEx. If there is no such certainty, the caller should set this parameter to `false` (or don't specify a value). This flag is useful for those [[Task]]s whose first action can or must be executed in the DoSiThEx, as it informs them that they can immediately execute said action synchronously. This method is thread-safe when this parameter value is false.
 */
trait TaskDomain(assistant: TaskDomain.Assistant) { thisTaskDomain =>


	/**
	 * Queues the execution of the received [[Runnable]] in this $DoSiThEx. See [[TaskDomain.Assistant.queueForSequentialExecution]]
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
		${ TaskDomainMacros.queueForSequentialExecutionImpl('assistant, 'procedure) }
	}

	inline def reportFailure(cause: Throwable): Unit =
		${ TaskDomainMacros.reportFailureImpl('assistant, 'cause) }

	/**
	 * An [[ExecutionContext]] that uses the $DoSiThEx. See [[TaskDomain.assistant.queueForSequentialExecution]] */
	object ownSingleThreadExecutionContext extends ExecutionContext {
		def execute(runnable: Runnable): Unit = queueForSequentialExecution(runnable.run())

		def reportFailure(cause: Throwable): Unit = thisTaskDomain.reportFailure(cause)
	}

	/**
	 * Encapsulates one or more chained actions and provides operations to declaratively build complex tasks from simpler ones.
	 * This tool eliminates the need for state variables that determine behavior, as the code structure itself indicates the execution order.
	 *
	 * A task can encapsulate a [[Future]], making interoperability with them easier.
	 *
	 * This tool was created to simplify the implementation of an actor that needs to perform multiple tasks simultaneously (as opposed to a finite state machine), because the overhead of separating them into different actors would be significant.
	 *
	 * Instances of [[Task]] whose result is always the same follow the monadic laws. However, if the result depends on mutable variables or timing, these laws may be broken.
	 * For example, if a task `t` closes over a mutable variable from the environment that affects its execution result, the equality of two supposedly equivalent expressions like {{{t.flatMap(f).flatMap(g) == t.flatMap(a => f(a).flatMap(g))}}} could be compromised. This would depend on the timing of when the variable is mutated—specifically if the mutation occurs between the start and end of execution.
	 * This does not mean that [[Task]] implementations must avoid closing over mutable variables altogether. Rather, it highlights that if strict adherence to monadic laws is required by your business logic, you should ensure that the mutable variable is not modified during task execution.
	 * For deterministic behavior, it's sufficient that any closed-over mutable variable is only mutated and accessed by actions executed in sequence. This is why the contract centralizes execution in the $DoSiThEx: to maintain determinism, even when closing over mutable variables, provided they are mutated solely within the $DoSiThEx.
	 *
	 * Design note: [[Task]] is a member of [[TaskDomain]] to ensure that access to [[Task]] instances is restricted to the section of code where the owning [[TaskDomain]] is exposed.
	 *
	 * @tparam A the type of result obtained when executing this task.
	 */
	trait Task[+A] { thisTask =>

		/**
		 * This method performs the actions represented by the task and calls `onComplete` within the $DoSiThEx context when the task finishes, regardless of whether it succeeds or fails.
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
		 * @param onComplete The callback that must be invoked upon the completion of this task,
		 * either successfully or unsuccessfully. The implementation should call this callback 
		 * within the $DoSiThEx context.
		 *
		 * The implementation may assume that `onComplete` will either terminate normally or 
		 * fatally, but will not throw non-fatal exceptions.
		 */
		protected def engage(onComplete: Try[A] => Unit): Unit;

		/** A bridge to access the [[engage]] from macros in [[TaskDomainMacros]]. */
		private[taskflow] inline def engageBridge(onComplete: Try[A] => Unit): Unit = engage(onComplete)

//		inline def attempt2(inline isRunningInDoSiThEx: Boolean = false)(onComplete: Try[A] => Unit): Unit = {
//			${ TaskDomainMacros.attemptImpl(isRunningInDoSiThEx, 'assistant, thisTaskDomain, 'thisTask, 'onComplete) }
//		}

		/**
		 * Trigger the execution of this [[Task]].
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @param onComplete called when the execution of this task completes, successfully or not.
		 * Must not throw non-fatal exceptions: `onComplet` must either terminate normally or fatally, but never with a non-fatal exception.                  
		 * $isExecutedByDoSiThEx
		 */
		inline def attempt(inline isRunningInDoSiThEx: Boolean = false)(inline onComplete: Try[A] => Unit): Unit = {
			${ TaskDomainMacros.attemptImpl('isRunningInDoSiThEx, 'assistant, 'thisTask, 'onComplete)}
		}

		/** Triggers the execution of this [[Task]] and returns a [[Future]] of its result.
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @return a [[Future]] that will be completed when this [[Task]] is completed.
		 */
		def toFuture(isRunningInDoSiThEx: Boolean = false): Future[A] = {
			val promise = Promise[A]()
			attempt(isRunningInDoSiThEx)(promise.complete)
			promise.future
		}

		/**
		 * Triggers the execution of this [[Task]] and returns [[Future]] containing its result wrapped inside a [[Success]].
		 *
		 * Is equivalent to {{{ transform(Success.apply).toFuture(isRunningInDoSiThEx) }}}
		 *
		 * Useful when failures need to be propagated to the next for-expression (or for-binding).
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @return a [[Future]] that will be completed when this [[Task]] is completed.
		 */
		def toFutureHardy(isRunningInDoSiThEx: Boolean = false): Future[Try[A]] = {
			val promise = Promise[Try[A]]()
			attempt(isRunningInDoSiThEx)(tryA => promise.complete(Success(tryA)))
			promise.future
		}


		/** Triggers the execution of this [[Task]] ignoring the result.
		 *
		 * $threadSafe
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 */
		inline def attemptAndForget(isRunningInDoSiThEx: Boolean = false): Unit =
			attempt(isRunningInDoSiThEx)(_ => {})

		/** Triggers the execution of this [[Task]] noticing faulty results.
		 *
		 * @param isRunningInDoSiThEx $isRunningInDoSiThEx
		 * @param errorHandler called when the execution of this task completes with a failure. $isExecutedByDoSiThEx $unhandledErrorsAreReported
		 */
		def attemptAndForgetHandlingErrors(isRunningInDoSiThEx: Boolean = false)(errorHandler: Throwable => Unit): Unit =
			attempt(isRunningInDoSiThEx) {
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
		 * WARNING: `consumer` will not be called if this task is never completed or if it is completed with a failure.
		 * @param consumer called with this task result when it completes, if it ever does.
		 *  */
		def foreach(consumer: A => Unit): Task[Unit] =
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
		inline def transform[B](resultTransformer: Try[A] => Try[B]): Task[B] = new Transform(thisTask, resultTransformer)


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
		inline def transformWith[B](taskBBuilder: Try[A] => Task[B]): Task[B] =
			new Compose(thisTask, taskBBuilder)


		/**
		 * Transforms this task by applying the given function to the result, but only when it is successful. Analogous to [[Future.map]].
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
		inline def map[B](f: A => B): Task[B] = transform(_ map f)

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
		def flatMap[B](taskBBuilder: A => Task[B]): Task[B] = transformWith {
			case Success(a) => taskBBuilder(a);
			case Failure(e) => Task.failed(e);
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
		inline def withFilter(predicate: A => Boolean): Task[A] = new WithFilter(thisTask, predicate)

		/** Applies the side-effecting function to the result of this task, and returns a new task with the result of this task.
		 * This method allows to enforce many callbacks that receive the same value are executed in a specified order.
		 * Note that if one of the chained `andThen` callbacks returns a value or throws an exception, that result is not propagated to the subsequent `andThen` callbacks. Instead, the subsequent `andThen` callbacks are given the result of this task.
		 * ===Detailed description===
		 * Returns a task that, when executed:
		 *		- first executes this task;
		 *		- second applies the received function to the result and, if the evaluation finishes:
		 *			- normally, completes with the result of this task.
		 *			- abruptly with a non-fatal exception, reports the failure cause to [[TaskDomain.Assistant.reportFailure]] and completes with the result of this task.
		 *			- abruptly with a fatal exception, never completes.
		 *
		 * $threadSafe
		 *
		 * @param sideEffect a side-effecting function. The call to this function is wrapped in a try-catch block; however, unlike most other operators, unhandled non-fatal exceptions are not propagated to the result of the returned task. $isExecutedByDoSiThEx
		 */
		def andThen(sideEffect: Try[A] => Any): Task[A] = {
			transform { tryA =>
				try sideEffect(tryA)
				catch {
					case NonFatal(e) => reportFailure(e)
				}
				tryA
			}
		}

		/** Transforms this task applying the given partial function to failure results. This is like map but for the throwable. Analogous to [[Future.recover]].
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
		def recover[B >: A](pf: PartialFunction[Throwable, B]): Task[B] =
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
		 * @param pf función parcial que se aplica al resultado del primer paso si es fallido para crear la [[Task]] que se ejecutaría luego de ésta como parte de la Task dada. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 */
		def recoverWith[B >: A](pf: PartialFunction[Throwable, Task[B]]): Task[B] = {
			transformWith[B] {
				case Failure(t) => pf.applyOrElse(t, (e: Throwable) => new Immediate(Failure(e)));
				case sa@Success(_) => new Immediate(sa);
			}
		}

		/**
		 * Repeats this task until applying the received function yields [[Some]].
		 * ===Detailed description===
		 * Creates a [[Task]] that, when executed, it will:
		 * - execute this task producing the result `tryA`
		 * - apply `condition` to `(completedCycles, tryA)`. If the evaluation finishes:
		 * 		- abruptly, completes with the cause.
		 * 		- normally with `Some(tryB)`, completes with `tryB`
		 * 		- normally with `None`, goes back to the first step.
		 *
		 * $threadSafe
		 *
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that decides if the loop continues or not based on:
		 * 	- the number of already completed cycles,
		 * 	- and the result of the last execution of the `taskA`.
		 * The loop ends when this function returns a [[Some]]. Its content will be the final result.
		 *
		 * $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return a new [[Task]] that, when executed, repeatedly executes this task and applies the `condition` to the task's result until the function's result is [[Some]]. The result of this task is the contents of said [[Some]] unless any execution of the `taskA` or `condition` terminates abruptly in which case this task result is the cause.
		 * */
		inline def repeatedHardyUntilSome[B](maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Option[Try[B]]): Task[B] =
			new RepeatHardyUntilSome(thisTask, maxRecursionDepthPerExecutor)(condition)

		/**
		 * Creates a new [[Task]] that is executed repeatedly until it fails or applying a condition to its result and the number of already completed cycles yields [[Some]].
		 * ===Detailed description===
		 * Creates a [[Task]] that, when executed, it will:
		 * - execute this task and, if its results is:
		 * 		- `Failure(cause)`, completes with the same failure.
		 * 		- `Success(a)`, applies the `condition` to `(completedCycles, a)`. If the evaluation finishes:	
		 * 			- abruptly, completes with the cause.
		 * 			- normally with `Some(tryB)`, completes with `tryB`
		 * 			- normally with `None`, goes back to the first step.
		 *
		 * $threadSafe
		 *
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that decides if the loop continues or not based on:
		 * 	- the number of already completed cycles,
		 * 	- and the result of the last execution of the `taskA`.
		 * The loop ends when this function returns a [[Some]]. Its content will be the final result.
		 *
		 * $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return a new [[Task]] that, when executed, repeatedly executes this task and applies the `condition` to the task's result until the function's result is [[Some]]. The result of this task is the contents of said [[Some]] unless any execution of the `taskA` or `condition` terminates abruptly in which case this task result is the cause.
		 * */
		inline def repeatedUntilSome[B](maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Option[Try[B]]): Task[B] =
			repeatedHardyUntilSome(maxRecursionDepthPerExecutor) { (completedCycles, tryA) =>
				tryA match {
					case Success(a) => condition(completedCycles, a)
					case f: Failure[A] => Some(f.castTo[B])
				}
			}


		/** Like [[repeatedHardlyUntilSome]] but the condition is a [[PartialFunction]] instead of a function that returns [[Option]]. */
		inline def repeatedUntilDefined[B](maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, Try[A]), Try[B]]): Task[B] =
			repeatedHardyUntilSome(maxRecursionDepthPerExecutor)(Function.untupled(pf.lift))

		/**
		 * Repeats this [[Task]] while the given function returns [[None]].
		 * ===Detailed behavior:===
		 * Returns a [[Task]] that, when executed, it will:
		 *  - Apply the `condition` function to `(n, ts0)` where `n` is the number of already completed evaluations of it (starts with zero).
		 *  - If the evaluation finishes:
		 *  	- Abruptly, completes with the cause.
		 *  	- Normally returning `Some(b)`, completes with `b`.
		 *  	- Normally returning `None`, executes the `taskA` and goes back to the first step replacing `ts0` with the result.
		 *
		 * $threadSafe
		 *
		 * @param ts0 the value passed as second parameter to `condition` the first time it is evaluated.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `ta0` if no cycle has been done yet. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @tparam S a supertype of `A`
		 * @tparam B the type of the result of this task.
		 */
		inline def repeatedWhileNone[S >: A, B](ts0: Try[S], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[S]) => Option[B]): Task[B] =
			new RepeatHardyWhileNone[S, B](thisTask, ts0, maxRecursionDepthPerExecutor: Int)(condition)

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
		inline def repeatedWhileUndefined[S >: A, B](ts0: Try[S], maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, Try[S]), B]): Task[B] = {
			repeatedWhileNone(ts0, maxRecursionDepthPerExecutor)(Function.untupled(pf.lift));
		}

		/**
		 * Wraps this task into another that belongs to other [[TaskDomain]].
		 * Useful to chain [[Task]]'s operations that involve different [[TaskDomain]]s.
		 * ===Detailed behavior===
		 * Returns a task that, when executed, it will execute this task within this [[TaskDomain]]'s $DoSiThEx and complete with the same result.
		 * CAUTION: Avoid closing over the same mutable variable from two transformations applied to Task instances belonging to different [[TaskDomain]]s.
		 * Remember that all routines (e.g., functions, procedures, predicates, and callbacks) provided to [[Task]] methods are executed by the $DoSiThEx of the [[TaskDomain]] that owns the [[Task]] instance on which the method is called.
		 * Therefore, calling [[attempt]] on the returned task will execute the `onComplete` passed to it within the $DoSiThEx of the `otherTaskDomain`.
		 *
		 * $threadSafe
		 *
		 * @param otherTaskDomain the [[TaskDomain]] to which the returned task will belong.
		 * */
		inline def onBehalfOf(otherTaskDomain: TaskDomain): otherTaskDomain.Task[A] =
			otherTaskDomain.Task.foreign(thisTaskDomain)(this)

		/** Casts the type-path of this [[Task]] to the received [[TaskDomain]]. Usar con cautela.
		 * Esta operación no hace nada en tiempo de ejecución. Solo engaña al compilador para evitar que chille cuando se opera con [[Task]]s que tienen distinto type-path pero se sabe que corresponden al mismo actor ejecutor.
		 * Se decidió hacer que [[Task]] sea un inner class del actor ejecutor para detectar en tiempo de compilación cuando se usa una instancia fuera de dicho actor.
		 * Usar el type-path checking para detectar en tiempo de compilación cuando una [[Task]] está siendo usada fuera del actor ejecutor es muy valioso, pero tiene un costo: El chequeo de type-path es más estricto de lo necesario para este propósito y, por ende, el compilador reportará errores de tipo en situaciones donde se sabe que el actor ejecutor es el correcto. Esta operación ([[castTypePath()]]) está para tratar esos casos.
		 */
		def castTypePath[E <: TaskDomain](taskDomain: E): taskDomain.Task[A] = {
			assert(thisTaskDomain eq taskDomain);
			this.asInstanceOf[taskDomain.Task[A]]
		}
	}

	object Task {

		/** Creates a [[Task]] whose execution never ends.
		 *
		 * $threadSafe
		 *
		 * @return the task described in the method description.
		 * */
		inline def never: Task[Nothing] = Never

		/** Creates a [[Task]] whose result is calculated at the call site even before the task is constructed.  The result of its execution is always the provided value.
		 *
		 * $threadSafe
		 *
		 * @param tryA the value that the returned task will give as result every time it is executed.
		 * @return the task described in the method description.
		 */
		inline def immediate[A](tryA: Try[A]): Task[A] = new Immediate(tryA);

		/** Creates a [[Task]] that always succeeds with a result that is calculated at the call site even before the task is constructed. The result of its execution is always a [[Success]] with the provided value.
		 *
		 * $threadSafe
		 *
		 * @param a the value contained in the [[Success]] that the returned task will give as result every time it is executed.
		 * @return the task described in the method description.
		 */
		inline def successful[A](a: A): Task[A] = new Immediate(Success(a));

		/** Creates a [[Task]] that always fails with a result that is calculated at the call site even before the task is constructed. The result of its execution is always a [[Failure]] with the provided [[Throwable]]
		 *
		 * $threadSafe
		 *
		 * @param throwable the exception contained in the [[Failure]] that the returned task will give as result every time it is executed.
		 * @return the task described in the method description.
		 */
		inline def failed[A](throwable: Throwable): Task[A] = new Immediate(Failure(throwable));

		/**
		 * Creates a task whose result is calculated withing the $DoSiThEx.
		 * ===Detailed behavior===
		 * Creates a task that, when executed, evaluates the `resultSupplier` within the $DoSiThEx. If it finishes:
		 *		- abruptly, completes with a [[Failure]] with the cause.
		 *		- normally, completes with the evaluation's result.
		 *
		 * $$threadSafe
		 *
		 * @param resultSupplier the supplier of the result. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return the task described in the method description.
		 */
		inline def own[A](resultSupplier: () => Try[A]): Task[A] = new Own(resultSupplier);

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
		 * @param resultSupplier La acción que estará encapsulada en la Task creada. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return the task described in the method description.
		 */
		inline def mine[A](resultSupplier: () => A): Task[A] = new Own(() => Success(resultSupplier()));

		/** Create a [[Task]] that just waits the completion of the specified [[Future]]. The result of the task, when executed, is the result of the received [[Future]].
		 *
		 * $threadSafe
		 *
		 * @param resultFuture the future to wait for.
		 * @return the task described in the method description.
		 */
		inline def wait[A](resultFuture: Future[A]): Task[A] = new Wait(resultFuture);

		/** Creates a [[Task]] that, when executed, triggers the execution of a process in an alien executor, and waits its result, successful or not.
		 * The process executor is usually foreign but may be the $DoSiThEx.
		 *
		 * $threadSafe
		 *
		 * @param futureBuilder a function that starts the process and return a [[Future]] of its result. $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
		 * @return the task described in the method description.
		 */
		inline def alien[A](futureBuilder: () => Future[A]): Task[A] = new Alien(futureBuilder);

        /**
		 * Creates a [[Task]] that, when executed, triggers the execution of a task that belongs to another [[TaskDomain]] within that [[TaskDomain]]'s $DoSiThEx.
		 * $threadSafe
		 *
		 * @param foreignTaskDomain the [[TaskDomain]] to whom the `foreignTask` belongs.
		 * @return a task that belongs to this [[TaskDomain]] */
		inline def foreign[A](foreignTaskDomain: TaskDomain)(foreignTask: foreignTaskDomain.Task[A]): Task[A] = {
			if foreignTaskDomain eq thisTaskDomain then foreignTask.asInstanceOf[thisTaskDomain.Task[A]]
			else new Foreign(foreignTaskDomain)(foreignTask)
		}

		/**
		 * Creates a [[Task]] that simultaneously triggers the execution of two tasks and returns their results combined with the received function.
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
		inline def combine[A, B, C](taskA: Task[A], taskB: Task[B])(f: (Try[A], Try[B]) => Try[C]): Task[C] =
			new Combine(taskA, taskB, f)

		/**
		 * Creates a task that, when executed, simultaneously triggers the execution of all the [[Task]]s in the received list, and completes with a list containing their results in the same order.
		 *
		 * $threadSafe
		 *
		 * @param tasks the list of [[Task]]s that the returned task will trigger simultaneously to combine their results.
		 * @return the task described in the method description.
		 *
		 * */
		def sequence[A](tasks: List[Task[A]]): Task[List[A]] = {
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
		def whileRightRepeatHardy[A, B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Either[Try[B], Task[A]]): Task[B] =
			new WhileRightRepeatHardy[A, B](tryA0, maxRecursionDepthPerExecutor)(condition);

		/** Creates a new [[Task]] that, when executed, repeatedly constructs and executes tasks as long as the `condition` is met.
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
		def whileRightRepeat[A, B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Either[Try[B], Task[A]]): Task[B] = {
			whileRightRepeatHardy[A, B](Success(a0), maxRecursionDepthPerExecutor) { (completedCycles, tryA) =>
				tryA match {
					case Success(a) => condition(completedCycles, a)
					case Failure(cause) => Left(Failure(cause))
				}
			}
		}

		@deprecated("lo hice como ejercicio")
		def repeatUntilLeft2[A, B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Task[Either[Try[B], A]]): Task[B] =
			whileRightRepeatHardy[Either[Try[B], A], B](tryA0.map(Right(_)), maxRecursionDepthPerExecutor)((completedExecutionsCounter, previousState) =>
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
		 * */
		inline def repeatUntilLeft[A, B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Task[Either[Try[B], A]]): Task[B] =
			new RepeatUntilLeft(a0, maxRecursionDepthPerExecutor)(condition)


		/** Creates a new [[Task]] that, when executed, repeatedly constructs and executes tasks until it succeed or the `maxRetries` is reached.
		 * ===Detailed behavior:===
		 * When the returned [[Task]] is executed, it will:
		 * - Try to apply the function `taskBuilder` to the number of tries that were already done. If the evaluation completes:
		 *  	- Abruptly, completes with the cause.
		 *  	- Normally, executes the returned task and if the result is:
		 *			- `Failure(cause)`, completes with `Failure(cause)`.
		 *			- `Success(Right(b))`, completes with `Success(b)`.
		 *			- `Success(Left(a))`, compares the retries counter against `maxRetries` and if:
		 *				- `retriesCounter >= maxRetries`, completes with `Left(a)`
		 *				- `retriesCounter < maxRetries`, increments the `retriesCounter` (which starts at zero) and goes back to the first step.
		 *
		 * $threadSafe
		 * */
		inline def retryUntilRight[A, B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Task[Either[A, B]]): Task[Either[A, B]] =
			new RetryUntilRight[A, B](maxRetries, maxRecursionDepthPerExecutor)(taskBuilder)

		@deprecated("No se usa. Lo hice como ejercicio")
		private def retryUntilRight2[A, B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Task[Either[A, B]]): Task[Either[A, B]] =
			repeatUntilLeft[Null, Either[A, B]](null, maxRecursionDepthPerExecutor) { (triesCounter, _) =>
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
		def repeatInterleavedUntilLeft[A, B, R](a0: A, b0: B, maxRecursionDepthPerExecutor: Int = 9)(controlTaskBuilder: (Int, A, B) => Task[Either[Try[R], A]])(interleavedTaskBuilder: (Int, A) => Task[B]): Task[R] = {

			repeatUntilLeft[(A, B), R]((a0, b0), maxRecursionDepthPerExecutor) { (completedCycles, ab) =>
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

	final class Foreign[+A](foreignTaskDomain: TaskDomain)(foreignTask: foreignTaskDomain.Task[A]) extends Task[A] {
		override def engage(onComplete: Try[A] => Unit): Unit = {
			foreignTask.attempt() { tryA => queueForSequentialExecution(onComplete(tryA)) }
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
			taskA.attempt(true) { tryA =>
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
			taskA.attempt(true) {
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
			originalTask.attempt(true) { tryA =>
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
			taskA.attempt(true) { tryA =>
				val taskB =
					try taskBBuilder(tryA)
					catch {
						case NonFatal(e) => Task.failed(e)
					}
				taskB.attempt(true)(onComplete)
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
	final class Combine[+A, +B, +C](taskA: Task[A], taskB: Task[B], f: (Try[A], Try[B]) => Try[C]) extends Task[C] {
		override def engage(onComplete: Try[C] => Unit): Unit = {
			var ota: Option[Try[A]] = None;
			var otb: Option[Try[B]] = None;
			taskA.attempt(true) { tryA =>
				otb match {
					case None => ota = Some(tryA)
					case Some(tryB) =>
						val tryC =
							try f(tryA, tryB)
							catch {
								case NonFatal(e) => Failure(e)
							}
						onComplete(tryC)

				}
			}
			taskB.attempt(true) { tb =>
				ota match {
					case None => otb = Some(tb)
					case Some(ta) =>
						val tryC =
							try f(ta, tb)
							catch {
								case NonFatal(e) => Failure(e)
							}
						onComplete(tryC)
				}
			}
		}

		override def toString: String = deriveToString[Combine[A, B, C]](this)
	}


	/**
	 * A [[Task]] that executes the received task until applying the received function yields [[Some]].
	 * ===Detailed description===
	 * A [[Task]] that, when executed, it will:
	 *		- execute the `taskA` producing the result `tryA`
	 *		- apply `condition` to `(completedCycles, tryA)`. If the evaluation finishes:
	 *			- abruptly, completes with the cause.
	 *			- normally with `Some(tryB)`, completes with `tryB`
	 *			- normally with `None`, goes back to the first step.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param taskA the task to be repeated.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param condition function that decides if the loop continues or not based on:
	 *		- the number of already completed cycles,
	 *		- and the result of the last execution of the `taskA`.
	 * The loop ends when this function returns a [[Some]]. Its content will be the final result of this task.
	 *
	 * $isExecutedByDoSiThEx $unhandledErrorsArePropagatedToTaskResult
	 * */
	final class RepeatHardyUntilSome[+A, +B](taskA: Task[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Option[Try[B]]) extends Task[B] {

		override def engage(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param completedCycles number of already completed cycles.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case more synchronous scenario. */
			def loop(completedCycles: Int, recursionDepth: Int): Unit = {
				taskA.attempt(true) { tryA =>
					val conditionResult =
						try condition(completedCycles, tryA)
						catch {
							case NonFatal(cause) => Some(Failure(cause))
						}
					conditionResult match {
						case Some(tryB) =>
							onComplete(tryB);

						case None =>
							if (recursionDepth < maxRecursionDepthPerExecutor) {
								loop(completedCycles + 1, recursionDepth + 1)
							} else {
								queueForSequentialExecution(loop(completedCycles + 1, 0))
							}
					}
				}
			}

			loop(0, 0)
		}

		override def toString: String = deriveToString[RepeatHardyUntilSome[A, B]](this)
	}

	/**
	 * Task that, when executed, repeatedly executes a task while a condition return [[None]].
	 * ===Detailed behavior:===
	 * When this [[Task]] is executed, it will:
	 *  - Try to apply the `condition` function to `(n, ta0)` where `n` is the number of already completed evaluations of it.
	 *  - If the evaluation completes:
	 *  	- Abruptly, completes with the cause.
	 *  	- Normally, returning `Some(b)`, completes with `b`.
	 *  	- Normally, returning `None`, executes the `taskA` and goes back to the first step replacing `ta0` with the result.
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
	final class RepeatHardyWhileNone[+A, +B](taskA: Task[A], ta0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Option[B]) extends Task[B] {
		override def engage(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param completedCycles number of already completed cycles.
			 * @param lastTaskResult the result of the last task execution.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case more synchronous scenario. */
			def loop(completedCycles: Int, lastTaskResult: Try[A], recursionDepth: Int): Unit = {
				val conditionResult: Try[B] | None.type =
					try condition(completedCycles, lastTaskResult) match {
						case Some(b) => Success(b)
						case None => None
					} catch {
						case NonFatal(cause) => Failure(cause)
					}
				if conditionResult eq None then {
					taskA.attempt(true) { newTryA =>
						if recursionDepth < maxRecursionDepthPerExecutor then loop(completedCycles + 1, newTryA, recursionDepth + 1)
						else queueForSequentialExecution(loop(completedCycles + 1, newTryA, 0))
					}
				} else onComplete(conditionResult.asInstanceOf[Try[B]])

			}

			loop(0, ta0, 0)
		}

		override def toString: String = deriveToString[RepeatHardyWhileNone[A, B]](this)
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
		final class WhileRightRepeatHardy[+A, +B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(checkAndBuild: (Int, Try[A]) => Either[Try[B], Task[A]]) extends Task[B] {
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
							taskA.attempt(true) { newTryA =>
								if (recursionDepth < maxRecursionDepthPerExecutor) loop(completedCycles + 1, newTryA, recursionDepth + 1)
								else queueForSequentialExecution(loop(completedCycles + 1, newTryA, 0));
							}
					}
				}

				loop(0, tryA0, 0)
			}

			override def toString: String = deriveToString[WhileRightRepeatHardy[A, B]](this)
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
		final class RepeatUntilLeft[+A, +B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(buildAndCheck: (Int, A) => Task[Either[Try[B], A]]) extends Task[B] {
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
					task.attempt(true) {
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

			override def toString: String = deriveToString[RepeatUntilLeft[A, B]](this)
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
		final class RetryUntilRight[+A, +B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Task[Either[A, B]]) extends Task[Either[A, B]] {
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
					task.attempt(true) {
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

			override def toString: String = deriveToString[RetryUntilRight[A, B]](this)
		}

		/** A commitment to complete a [[Task]].
		 * Analogous to [[scala.concurrent.Promise]] but for a [[Task]] instead of a [[scala.concurrent.Future]].
		 * */
		final class Commitment[A] { thisCommitment =>
			private var oResult: Option[Try[A]] = None;
			private var onCompletedObservers: List[Try[A] => Unit] = Nil;

			/** @return true if this [[Commitment]] was either fulfilled or broken; or false if it is still pending. */
			def isCompleted: Boolean = this.oResult.isDefined;

			/** @return true if this [[Commitment]] is still pending; or false if it was completed. */
			def isPending: Boolean = this.oResult.isEmpty;

			/** The [[Task]] this [[Commitment]] promises to complete. This task's will complete when this [[Commitment]] is fulfilled or broken. That could be done immediately calling [[fulfill]], [[break]], [[complete]], or in a deferred manner by calling [[completeWith]]. */
			val task: Task[A] = (onComplete: Try[A] => Unit) => {
				thisCommitment.oResult match {
					case Some(result) =>
						onComplete(result);
					case None =>
						thisCommitment.onCompletedObservers = onComplete :: thisCommitment.onCompletedObservers;
				}
			}

			/** Provokes that the [[task]] that this [[Commitment]] promises to complete to be completed with the received `result`.
			 *
			 * @param result the result that the [[task]] this [[Commitment]] promised to complete . */
			def complete(result: Try[A])(onAlreadyCompleted: Try[A] => Unit = _ => ()): this.type = {
				queueForSequentialExecution {
					oResult match {
						case None =>
							this.oResult = Some(result);
							this.onCompletedObservers.foreach(_(result));
							// la lista de observadores quedó obsoleta. Borrarla para minimizar posibilidad de memory leak.
							this.onCompletedObservers = Nil
						case Some(value) =>
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
					otherTask.attempt()(result => complete(result)(onAlreadyCompleted));
				this
			}
		}

		//////////////////////////////////////

		object Flow {
			def lift[A, B](f: A => B): Flow[A, B] =
				(a: A) => Task.successful(f(a))

			def wrap[A, B](taskBuilder: A => Task[B]): Flow[A, B] =
				(a: A) => taskBuilder(a)
		}

		trait Flow[A, B] { thisFlow =>

			protected def attempt(a: A): Task[B]

			inline def apply(a: A, inline isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
				def work(): Unit = {
					try attempt(a).attempt(true)(onComplete)
					catch {
						case NonFatal(e) => onComplete(Failure(e))
					}
				}

				if isRunningInDoSiThEx then work()
				else queueForSequentialExecution(work())
			}

			/** Connects this flow output with the input of the received one. */
			def to[C](next: Flow[B, C]): Flow[A, C] =
				(a: A) => thisFlow.attempt(a).flatMap(b => next.attempt(b))

			/** Connects the received flow output with the input of this one. */
			def from[Z](previous: Flow[Z, A]): Flow[Z, B] =
				(z: Z) => previous.attempt(z).flatMap(a => thisFlow.attempt(a))
		}
	}