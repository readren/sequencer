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
 * @define executedByDoSiThEx Executed within the DoSiThEx (domain's single-thread executor).
 * @define isGuarded This function is guarded with try-catch. If it throws a non-fatal error the [[Task]] will complete with it as the cause of failure.
 * @define maxRecursionDepthPerExecutor Maximum recursion depth per executor. Once this limit is reached, the recursion continues in a new executor. The result does not depend on this parameter as long as no [[StackOverflowError]] occurs.
 */
trait TaskDomain(assistant: TaskDomain.Assistant) { thisTaskContext =>


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
	inline def queueForSequentialExecution(runnable: Runnable): Unit = {
		assistant.queueForSequentialExecution(runnable)
	}


	/**
	 * An [[ExecutionContext]] that uses the $DoSiThEx. See [[TaskDomain.assistant.queueForSequentialExecution]] */
	object ownSingleThreadExecutionContext extends ExecutionContext {
		def execute(runnable: Runnable): Unit = assistant.queueForSequentialExecution(runnable)

		def reportFailure(cause: Throwable): Unit = assistant.reportFailure(cause)
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
		 * The implementation must trigger the execution of this [[Task]] and then ensure the callback function `onComplete` is called within the $DoSiThEx when the task's execution finishes, either normally or abnormally.
		 * The implementation should be thread-safe.
		 * This is the only primitive method of this trait. All the other derive from this one.
		 *
		 * @param isRunningInDoSiThEx indicates whether the caller is certain that this method is being executed within the $DoSiThEx. If there is no such certainty, this parameter should be set to `false`.
		 * This flag is useful for those [[Task]]s whose first action can or must be executed in the DoSiThEx, as it informs them that they can immediately execute said action synchronously.
		 * @param onComplete callback that the implementation must invoke when the execution of this task completes, successfully or not.
		 * The implementation must ensure the call to this call-back occurs within the $DoSiThEx.
		 * The implementation may assume that `onComplete` does not throw non-fatal exceptions: it must either terminate normally or fatally, but never with a non-fatal exception.
		 */
		def attempt(isRunningInDoSiThEx: Boolean = false)(onComplete: Try[A] => Unit): Unit;

		def engage(onComplete: Try[A] => Unit): Unit = attempt(false) { tryA =>
			try onComplete(tryA) catch {
				case NonFatal(cause) => assistant.reportFailure(cause)
			}
		}

		/** Triggers the execution of this [[Task]] and returns a [[Future]] of its result.
		 *
		 * $threadSafe
		 *
		 * @return a [[Future]] that will be completed when this [[Task]] is completed.
		 */
		def promiseAttempt(isRunningInDoSiThEx: Boolean = false): Future[A] = {
			val promise = Promise[A]()
			attempt(isRunningInDoSiThEx)(promise.complete)
			promise.future
		}

		/** Ejecuta esta [[Task]] ignorando tanto el resultado como si terminó normal o abruptamente.
		 *
		 * $threadSafe
		 */
		inline def attemptAndForget(isRunningInDoSiThEx: Boolean = false): Unit =
			attempt(isRunningInDoSiThEx)(_ => {})

		/** Ejecuta esta [[Task]] y si termina abruptamente llama a la función recibida.
		 * La función `manejadorError` recibida será ejecutada por este actor si la acción encapsulada termina abruptamente.
		 *
		 * $threadSafe
		 */
		inline def attemptAndForgetHandlingErrors(isRunningInDoSiThEx: Boolean = false)(errorHandler: Throwable => Unit): Unit =
			attempt(isRunningInDoSiThEx) {
				case Failure(e) =>
					try errorHandler(e) catch {
						case NonFatal(cause) => assistant.reportFailure(cause)
					}
				case _ => ()
			}

		/** Da una [[Task]] que al ejecutarla:
		 *  - primero ejecuta ésta Task,
		 *  - segundo aplica la función `f` al resultado
		 * 	- último llama a `onComplete` con:
		 *  	- la excepción lanzada en el segundo paso por la operación `ctorTask` si termina abruptamente,
		 *  	- o el resultado de la aplicación del segundo paso.
		 *      Análogo a Future.transform.
		 *
		 * $threadSafe
		 *
		 * @param resultTransformer función que se aplica al resultado de esta [[Task]] para determinar el resultado de la Task dada. $executedByDoSiThEx $isGuarded
		 */
		inline def transform[B](resultTransformer: Try[A] => Try[B]): Task[B] = new Transform(thisTask, resultTransformer)


		/** Composes two [[Task]]s where the second is build from the result of the first, analogous to [[Future.transformWith]].
		 * Detailed behavior: Gives a [[Task]] which, if executed, would:
		 *  - first execute this Task, and if that terminates normally:
		 *  	- second applies the function [[taskBBuilder]] to the result of step one, and if that terminates normally:
		 *  		- third executes the [[Task]] created in the second step;
		 *  - finally calls [[onComplete]] passing either:
		 *  	- if the first three steps terminated normally, the result of the task created in the 3rd step, which may be a [[Failure]].
		 *  	- if any of them terminated abruptly, the exception thrown there 
		 *      The behavior of this method is analogous to [[Future.transformWith]].
		 *      The [[taskBBuilder]] is executed by the $DoSiThEx.
		 *
		 * $threadSafe
		 *
		 * @param taskBBuilder función que se aplica al resultado de esta [[Task]] para crear la [[Task]] que se ejecutará después. $executedByDoSiThEx $isGuarded
		 */
		inline def transformWith[B](taskBBuilder: Try[A] => Task[B]): Task[B] =
			new Compose(thisTask, taskBBuilder)


		/** Da una [[Task]] que al ejecutarla primero hace esta Task y luego, si  es exitoso, aplica la función `f` al resultado.
		 *
		 * $threadSafe
		 *
		 * @param f función que transforma el resultado de la Task. $executedByDoSiThEx $isGuarded
		 */
		inline def map[B](f: A => B): Task[B] = transform(_ map f)

		/** Da una [[Task]] que al ejecutarla:
		 *  - primero ejecuta esta [[Task]], y si el resultado es exitoso:
		 *  	- segundo aplica la función `taskBBuilder` al resultado, y si `taskBBuilder` termina normalmente:
		 *  		- tercero ejecuta la Task creada en el paso anterior.
		 *  - último llama a `onComplete` con:
		 *  	- el resultado de la ejecución del primer paso si es fallido;
		 *  	- la excepción lanzada en el segundo paso por la operación `ctorTask` si termina abruptamente;
		 *  	- o el resultado de la ejecución del tercer paso.
		 *
		 * $threadSafe
		 *
		 * @param taskBBuilder función que se aplica al resultado del primer paso para crear la [[Task]] que se ejecutaría luego de ésta como parte de la Task dada. $executedByDoSiThEx $isGuarded
		 */
		def flatMap[B](taskBBuilder: A => Task[B]): Task[B] = transformWith {
			case Success(s) => taskBBuilder(s);
			case Failure(e) => new Immediate(Failure(e)); //this.asInstanceOf[Task[B]]
		}

		/** Needed to support filtering and case matching in for-compressions. The for-expressions (or for-bindings) after the filter are not executed if the [[predicate]] is not satisfied.
		 * Detailed behavior: Gives a [[Task]] that, if executed, it would:
		 * 	- first executes this [[Task]], and if terminates normally:
		 * 		- second applies the [[predicate]] to the result of the first step;
		 * 	- finally calls the [[onComplete]] passing either:
		 *      if the second step terminated:
		 * 			- normally and the result is:
		 * 				- true, a [[Success]] containing the result of the first step;
		 * 				- false, a [[Failure]] containing [[NoSuchElementException]];
		 * 			- abruptly, a [[Failure]] containing the cause.
		 *
		 * $threadSafe
		 * */
		def withFilter(predicate: A => Boolean): Task[A] = new Task[A] {
			def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit = {
				val runnable: Runnable = () =>
					thisTask.attempt(true) {
						case s@Success(a) =>
							try {
								if (predicate(a))
									onComplete(s)
								else
									onComplete(Failure(new NoSuchElementException(s"Task filter predicate is not satisfied for $a")))
							} catch {
								case NonFatal(e) => onComplete(Failure(e))
							}

						case f@Failure(_) =>
							onComplete(f)
					}

				if (isRunningInDoSiThEx) runnable.run();
				else assistant.queueForSequentialExecution(runnable)
			}
		}

		/** Encadena un efecto secundario.
		 *
		 * El resultado de la Task dada es el mismo que daría esta Task, ignorando como termine la ejecución de `p` y su resultado.
		 * Para quien no ve el efecto secundario la diferencia entre esta Task y la dada es solo el tiempo que consume la ejecución.
		 *
		 * Específicamente hablando, da una [[Task]] que si se ejecutase:
		 * 	- primero se ejecutaría esta Task:
		 * 	- segundo se aplicaría la función `p` al resultado de esta [[Task]].
		 * 	- ultimo se llamaría a `onComplete` con:
		 * 		- el resultado de la ejecución del primer paso sea exitoso o fallido;
		 *
		 * Es equivalente a:
		 * {{{
		 * 		transform[A] { ta =>
		 * 			try p(ta)
		 * 		catch { case NonFatal(e) => log.error(e, "..") }
		 * 	}
		 * }}}
		 *
		 * $threadSafe
		 *
		 * @param p función parcial que representa el efecto secundario. El resultado de esta función parcial es ignorado.  $executedByDoSiThEx
		 */
		def andThen(p: Try[A] => Any): Task[A] = {
			transform {
				ta =>
					try p(ta)
					catch {
						case NonFatal(e) => assistant.reportFailure(e)
					}
					ta
			}
		}

		/** Como `map` pero para las fallas.
		 * Da una [[Task]] que al ejecutarla:
		 *  - primero ejecuta esta Task y, si el resultado es fallido y la función parcial `pf` esta definida para dicha falla:
		 *  	- segundo aplica la función parcial `pf` a la falla;
		 *  - último llama a `onComplete` con:
		 *  	- el resultado del primero paso si es exitoso, o fallido donde la función parcial `pf` no este definida;
		 *  	- la excepción lanzada en el segundo paso por la función `pf` si termina abruptamente;
		 *  	- o el resultado del segundo paso.
		 *      Análogo a Future.recover.
		 *
		 * $threadSafe
		 *
		 * @param pf función parcial que se aplica al resultado del primer paso si es fallido para determinar el resultado de la Task dada. $executedByDoSiThEx $isGuarded
		 */
		def recover[B >: A](pf: PartialFunction[Throwable, B]): Task[B] =
			transform {
				_.recover(pf)
			}

		/** Como `flatMap` pero para las fallas
		 * Da una [[Task]] que al ejecutarla:
		 *  - primero ejecuta esta Task, y si el resultado es fallido y la función parcial `pf` esta definida para dicha falla:
		 *  	- segundo aplica la función parcial `pf` a la falla, y si `pf` termina normalmente:
		 *  		- tercero ejecuta la Task creada en el paso anterior;
		 *  - último llama a `onComplete` con:
		 *  	- el resultado del primero paso si es exitoso o fallido donde la función parcial `pf` no este definida;
		 *  	- la excepción lanzada en el segundo paso por la función parcial `pf` si termina abruptamente;
		 *  	- o el resultado del tercer paso.
		 *      Análogo a Future.recoverWith.
		 *
		 * $threadSafe
		 *
		 * @param pf función parcial que se aplica al resultado del primer paso si es fallido para crear la [[Task]] que se ejecutaría luego de ésta como parte de la Task dada. $executedByDoSiThEx $isGuarded
		 */
		def recoverWith[B >: A](pf: PartialFunction[Throwable, Task[B]]): Task[B] = {
			transformWith[B] {
				case Failure(t) => pf.applyOrElse(t, (e: Throwable) => new Immediate(Failure(e)));
				case sa@Success(_) => new Immediate(sa);
			}
		}

		/**
		 * Creates a new [[Task]] that is executed repeatedly until applying a condition to its result and the number of already completed cycles yields [[Some]].
		 * ===Detailed description===
		 * Creates a [[Task]] that, when executed, it will:
		 * - execute this task producing the result `tryA`
		 * - apply `condition` to `(completedCycles, tryA)`. If the evaluation finishes:
		 * 		- abruptly, completes with the cause.
		 * 		- normally with `Some(tryB)`, completes with `tryB`
		 * 		- normally with `None`, goes back to the first step.
		 *
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that decides if the loop continues or not based on:
		 * 	- the number of already completed cycles,
		 * 	- and the result of the last execution of the `taskA`.
		 *
		 * $executedByDoSiThEx $isGuarded
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
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition function that decides if the loop continues or not based on:
		 * 	- the number of already completed cycles,
		 * 	- and the result of the last execution of the `taskA`.
		 *
		 * $executedByDoSiThEx $isGuarded
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
		 * Creates a task that, when executed, repeatedly executes this [[Task]] while a condition returns [[None]].
		 * ===Detailed behavior:===
		 * When this [[Task]] is executed, it will:
		 *  - Try to apply the `condition` function to `(n, ts0)` where `n` is the number of already completed evaluations of it.
		 *  - If the evaluation completes:
		 *  	- Abruptly, completes with the cause.
		 *  	- Normally, returning `Some(b)`, completes with `b`.
		 *  	- Normally, returning `None`, executes the `taskA` and goes back to the first step replacing `ts0` with the result.
		 *
		 * $onCompleteExecutedByDoSiThEx
		 *
		 * @param ts0 the value passed as second parameter to `condition` the first time it is evaluated.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param condition determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `ta0` if no cycle has been done yet. $executedByDoSiThEx $isGuarded
		 * @tparam S a supertype of `A`
		 * @tparam B the type of the result of this task.
		 */
		inline def repeatedWhileNone[S >: A, B](ts0: Try[S], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[S]) => Option[B]): Task[B] =
			new RepeatHardyWhileNone[S, B](thisTask, ts0, maxRecursionDepthPerExecutor: Int)(condition)

		/**
		 * Creates a task that, when executed, repeatedly executes this [[Task]] while a [[PartialFunction]] is undefined.
		 * ===Detailed behavior:===
		 * When this [[Task]] is executed, it will:
		 *  - Check if the partial function is defined in `(n, ts0)` where `n` is the number of already completed evaluations of it. If it:
		 *		- fails, completes with the cause.
		 *  	- is undefined, executes the `taskA` and goes back to the first step replacing `ts0` with the result.
		 *  	- is defined, evaluates it and if it finishes:
		 *			- abruptly, completes with the cause.
		 *			- normally, completes with the result.
		 *
		 * $onCompleteExecutedByDoSiThEx
		 *
		 * @param ts0 the value passed as second parameter to `condition` the first time it is evaluated.
		 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
		 * @param pf determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `ta0` if no cycle has been done yet. $executedByDoSiThEx $isGuarded
		 * @tparam S a supertype of `A`
		 * @tparam B the type of the result of this task.
		 */
		inline def repeatedWhileUndefined[S >: A, B](ts0: Try[S], maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, Try[S]), B]): Task[B] = {
			repeatedWhileNone(ts0, maxRecursionDepthPerExecutor)(Function.untupled(pf.lift));
		}

		/** Casts the type-path of this [[Task]] to the received [[TaskDomain]]. Usar con cautela.
		 * Esta operación no hace nada en tiempo de ejecución. Solo engaña al compilador para evitar que chille cuando se opera con [[Task]]s que tienen distinto type-path pero se sabe que corresponden al mismo actor ejecutor.
		 * Se decidió hacer que [[Task]] sea un inner class del actor ejecutor para detectar en tiempo de compilación cuando se usa una instancia fuera de dicho actor.
		 * Usar el type-path checking para detectar en tiempo de compilación cuando una [[Task]] está siendo usada fuera del actor ejecutor es muy valioso, pero tiene un costo: El chequeo de type-path es más estricto de lo necesario para este propósito y, por ende, el compilador reportará errores de tipo en situaciones donde se sabe que el actor ejecutor es el correcto. Esta operación ([[castTypePath()]]) está para tratar esos casos.
		 */
		def castTypePath[E <: TaskDomain](taskDomain: E): taskDomain.Task[A] = {
			assert(thisTaskContext eq taskDomain);
			this.asInstanceOf[taskDomain.Task[A]]
		}
	}

	object Task {

		/** Creates a [[Task]] whose execution never ends.
		 *
		 * $threadSafe */
		inline def never: Task[Nothing] = new Never

		/** Creates a [[Task]] whose result is immediately given. The result of its execution is always the given value.
		 *
		 * $threadSafe
		 */
		inline def immediate[A](tryA: Try[A]): Task[A] = new Immediate(tryA);

		/** Creates a [[Task]] whose result is immediately given and always succeeds. The result of its execution is always a [[Success]] with the given value.
		 *
		 * $threadSafe
		 */
		inline def successful[A](a: A): Task[A] = new Immediate(Success(a));

		/** Creates a [[Task]] whose result is immediately given and always fails. The result of its execution is always a [[Failure]] with the given [[Throwable]]
		 *
		 * $threadSafe
		 */
		inline def failed[A](throwable: Throwable): Task[A] = new Immediate(Failure(throwable));

		/** Crea una Task que al ejecutarla programa la realización por parte de este actor de la `acción` recibida.
		 * Y luego, después que la `acción` haya sido efectuada, llama a `onComplete` pasándole:
		 * - el resultado si `acción` termina normalmente;
		 * - o `Failure(excepción)` si la `acción` termina abruptamente.
		 *
		 * $$threadSafe
		 *
		 * @param resultBuilder La acción que estará encapsulada en la Task creada. $executedByDoSiThEx
		 */
		inline def own[A](resultBuilder: () => Try[A]): Task[A] = new Own(resultBuilder);

		/** Crea una Task que al ejecutarla programa la realización por parte de este actor de la `acción` recibida.
		 * Y luego, después que la `acción` haya sido efectuada, llama a `onComplete` pasándole:
		 * - `Success(resultado)` si la `acción` termina normalmente;
		 * - o `Failure(excepción)` si la `acción` termina abruptamente.
		 *
		 * Equivale a {{{ propia { () => Success(accion()) } }}}
		 *
		 * $threadSafe
		 *
		 * @param resultBuilder La acción que estará encapsulada en la Task creada. $executedByDoSiThEx
		 *
		 */
		inline def mine[A](resultBuilder: () => A): Task[A] = new Own(() => Success(resultBuilder()));

		/** Create a [[Task]] that just waits the completion of the specified [[Future]]. The result of the task, when executed, is the result of the received [[Future]].
		 *
		 * $threadSafe
		 *
		 * @param resultFuture the future to wait for.
		 */
		inline def wait[A](resultFuture: Future[A]): Task[A] = new Wait(resultFuture);

		/** Creates a [[Task]] that, when executed, triggers the execution of a process in an alien executor and waits its result, successful or not.
		 * The process executor may be the $DoSiThEx but if that is the intention [[Own]] would be more appropriate.
		 *
		 * $threadSafe
		 *
		 * @param futureBuilder a function that starts the process and return a [[Future]] of its result.
		 */
		inline def alien[A](futureBuilder: () => Future[A]): Task[A] = new Alien(futureBuilder);

		/** Crea una tarea que al ejecutarla:
		 *  - primero ejecuta las `tareaA` y `tareaB` en simultaneo (en oposición a esperar que termine una para ejecutar la otra)
		 *  - segundo, cuando ambas terminan, sea exitosamente o fallidamente, se aplica la función `f` al resultado de las dos tareas.
		 *  - ultimo llama a `cuandooCompleta`con:
		 *  	- la excepción lanzada por la función `f` si termina abruptamente;
		 *  	- o el resultado de f si termina normalmente
		 *
		 * $threadSafe
		 */
		inline def combine[A, B, C](taskA: Task[A], taskB: Task[B])(f: (Try[A], Try[B]) => Try[C]): Task[C] =
			new Combine(taskA, taskB, f)

		/** Crea una tarea que al ejecutarla ejecuta las tareas recibidas en paralelo, y da como resultado una lista con sus resultados en el mismo orden. */
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
		 *          WARNING: the execution of the returned task will never end if the control task always returns [[Right]].
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
	final class Never extends Task[Nothing] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[Nothing] => Unit): Unit = ()
	}

	/** Task que al ejecutarla da como resultado el valor recibido.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 */
	final class Immediate[+A](tryA: Try[A]) extends Task[A] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit =
			if (isRunningInDoSiThEx)
				onComplete(tryA)
			else
				assistant.queueForSequentialExecution(() => onComplete(tryA))

		override def promiseAttempt(isRunningInDoSiThEx: Boolean): Future[A] =
			Future.fromTry(tryA)
	}

	/** A [[Task]] that, when executed, calls the `resultSupplier` within the $DoSiThEx and if it finishes:
	 *  - abruptly, completes with the cause.
	 *  - normally, completes with the result.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param resultSupplier builds the result of this [[Task]]. $executedByDoSiThEx $isGuarded
	 */
	final class Own[+A](resultSupplier: () => Try[A]) extends Task[A] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit = {
			def tryResultBuilder() =
				try resultSupplier()
				catch {
					case NonFatal(e) => Failure(e)
				}

			if (isRunningInDoSiThEx) {
				onComplete(tryResultBuilder())
			} else {
				assistant.queueForSequentialExecution(() => onComplete(tryResultBuilder()))
			}
		}
	}

	/** A [[Task]] that just waits the completion of the specified [[Future]]. The result of the task, when executed, is the result of the received [[Future]].
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param resultFuture the future to wait for.
	 */
	final class Wait[+A](resultFuture: Future[A]) extends Task[A] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit =
			resultFuture.onComplete(onComplete)(ownSingleThreadExecutionContext)
	}

	/** A [[Task]] that, when executed, triggers the execution of a process in an alien executor and waits its result, successful or not.
	 * The process executor may be the $DoSiThEx but if that is the intention [[Own]] would be more appropriate.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param futureBuilder a function that starts the process and return a [[Future]] of its result.
	 * */
	final class Alien[+A](futureBuilder: () => Future[A]) extends Task[A] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit =
			futureBuilder().onComplete(onComplete)(ownSingleThreadExecutionContext)
	}

	/** [[Task]] que al ejecutarla:
	 *  - primero ejecuta la `Task` recibida;
	 *  - segundo aplica la función `f` al resultado y, si la aplicación termina normalmente:
	 *  	- tercero ejecuta la Task creada;
	 *  - último llama a `onComplete` con:
	 *  	- la excepción lanzada en el segundo paso por la operación `ctorTask` si termina abruptamente;
	 *  	- o el resultado de la ejecución del tercer paso.
	 *
	 * The [[resultTransformer]] is executed by the $DoSiThEx.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param resultTransformer función que se aplica al resultado de esta [[Task]] para crear la [[Task]] que se ejecutará después. $executedByDoSiThEx $isGuarded
	 */
	final class Transform[+A, +B](originalTask: Task[A], resultTransformer: Try[A] => Try[B]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			originalTask.attempt(isRunningInDoSiThEx)(ta => onComplete {
				try resultTransformer(ta) catch {
					case NonFatal(e) => Failure(e);
				}
			});
		}
	}


	/** Task que representa la composición de dos Tasks donde la segunda es creada a partir del resultado de la primera.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param taskA Task que se ejecutaría antes
	 * @param taskBBuilder constructor de la Task que se ejecutaría después. Este constructor es ejecutado por este actor cuando la primer Task terminó.
	 */
	final class Compose[+A, +B](taskA: Task[A], taskBBuilder: Try[A] => Task[B]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit =
			taskA.attempt(isRunningInDoSiThEx) { resultA =>
				try
					taskBBuilder(resultA).attempt(true)(onComplete);
				catch {
					case NonFatal(e) => onComplete(Failure(e))
				}
			}
	}


	/** [[Task]] que al ejecutarla:
	 *  - primero ejecuta las `tareaA` y `tareaB` en simultaneo (en oposición a esperar que termine una para ejecutar la otra)
	 *  - segundo, cuando ambas tareas terminan, sea exitosamente o fallidamente, se aplica la función `f` al resultado de las dos tareas.
	 *  - ultimo llama a `cuandooCompleta`con:
	 *  	- la excepción lanzada por la función `f` si termina abruptamente;
	 *  	- o el resultado de f si termina normalmente
	 *
	 * $onCompleteExecutedByDoSiThEx
	 */
	final class Combine[+A, +B, +C](taskA: Task[A], taskB: Task[B], f: (Try[A], Try[B]) => Try[C]) extends Task[C] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[C] => Unit): Unit = {
			var ota: Option[Try[A]] = None;
			var otb: Option[Try[B]] = None;
			taskA.attempt(isRunningInDoSiThEx) { ta =>
				otb match {
					case None => ota = Some(ta)
					case Some(tb) => onComplete {
						try {
							f(ta, tb)
						} catch {
							case NonFatal(e) => Failure(e)
						}
					}
				}
			}
			taskB.attempt(isRunningInDoSiThEx) { tb =>
				ota match {
					case None => otb = Some(tb)
					case Some(ta) => onComplete {
						try {
							f(ta, tb)
						} catch {
							case NonFatal(e) => Failure(e)
						}
					}
				}
			}
		}
	}


	/**
	 * A [[Task]] that executes a task until applying a condition to its result yields [[Some]].
	 * ===Detailed description===
	 * A [[Task]] that, when executed, it will:
	 * - execute the `taskA` producing the result `tryA`
	 * - apply `condition` to `(completedCycles, tryA)`. If the evaluation finishes:
	 * 		- abruptly, completes with the cause.
	 * 		- normally with `Some(tryB)`, completes with `tryB`
	 * 		- normally with `None`, goes back to the first step.
	 *
	 * $onCompleteExecutedByDoSiThEx
	 *
	 * @param taskA the task to be repeated.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param condition function that decides if the loop continues or not based on:
	 * 	- the number of already completed cycles,
	 * 	- and the result of the last execution of the `taskA`.
	 *
	 * $executedByDoSiThEx $isGuarded
	 * */
	final class RepeatHardyUntilSome[+A, +B](taskA: Task[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Option[Try[B]]) extends Task[B] {

		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param completedCycles number of already completed cycles.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case more synchronous scenario. */
			def loop(completedCycles: Int, recursionDepth: Int): Unit = {
				taskA.attempt(isRunningInDoSiThEx = true) { tryA =>
					try {
						condition(completedCycles, tryA) match {
							case Some(tryB) =>
								onComplete(tryB);

							case None =>
								if (recursionDepth < maxRecursionDepthPerExecutor) {
									loop(completedCycles + 1, recursionDepth + 1)
								} else {
									queueForSequentialExecution(() => loop(completedCycles + 1, 0))
								}
						}
					} catch {
						case NonFatal(cause) =>
							onComplete(Failure(cause))
					}
				}
			}

			if (isRunningInDoSiThEx) loop(0, 0);
			else queueForSequentialExecution(() => loop(0, 0));
		}
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
	 * @param condition determines whether a new cycle should be performed based on the number of times it has already been evaluated and either the result of the previous cycle or `ta0` if no cycle has been done yet. $executedByDoSiThEx $isGuarded
	 * @tparam A the type of the result of the repeated task `taskA`.
	 * @tparam B the type of the result of this task.
	 */
	final class RepeatHardyWhileNone[+A, +B](taskA: Task[A], ta0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Option[B]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param completedCycles number of already completed cycles.
			 * @param lastTaskResult the result of the last task execution.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case more synchronous scenario. */
			def loop(completedCycles: Int, lastTaskResult: Try[A], recursionDepth: Int): Unit = {
				try {
					condition(completedCycles, lastTaskResult) match {
						case Some(b) => onComplete(Success(b))
						case None => taskA.attempt(true) { newTryA =>
							if recursionDepth < maxRecursionDepthPerExecutor then loop(completedCycles + 1, newTryA, recursionDepth + 1)
							else queueForSequentialExecution(() => loop(completedCycles + 1, newTryA, 0))
						}
					}
				} catch {
					case NonFatal(cause) => onComplete(Failure(cause))
				}
			}

			if (isRunningInDoSiThEx) loop(0, ta0, 0);
			else queueForSequentialExecution(() => loop(0, ta0, 0));
		}
	}


	/** Task that, when executed, repeatedly constructs and executes tasks as long as the `condition` is met.
	 * ===Detailed behavior:===
	 * When this [[Task]] is executed, it will:
	 *  - Try to apply the function `checkAndBuild` to `(n, tryA0)` where `n` is the number of already completed cycles, and if it completes:
	 *  	- Abruptly, completes with the cause.
	 *		- Normally, returning a `Left(tryB)`, completes with `tryB`.
	 *  		- Normally, returning a `Right(taskA)`, executes the `taskA` and goes back to the first step replacing `tryA0` with the result.
	 *
	 * @param tryA0 the initial value wrapped in a `Try`, used in the first call to `checkAndBuild`.
	 * @param maxRecursionDepthPerExecutor $maxRecursionDepthPerExecutor
	 * @param checkAndBuild a function that takes the number of already completed cycles and the last task result wrapped in a `Try`, returning an `Either[Try[B], Task[A]]` indicating the next action to perform.	$executedByDoSiThEx $isGuarded *
	 */
	final class WhileRightRepeatHardy[+A, +B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(checkAndBuild: (Int, Try[A]) => Either[Try[B], Task[A]]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param completedCycles number of already completed cycles, which consist of a task creation and its execution.
			 * @param lastTaskResult the result of the last task execution.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(completedCycles: Int, lastTaskResult: Try[A], recursionDepth: Int): Unit = {
				try {
					checkAndBuild(completedCycles, lastTaskResult) match {
						case Left(tryB) =>
							onComplete(tryB);
						case Right(taskA) =>
							taskA.attempt(isRunningInDoSiThEx = true) { newTryA =>
								if (recursionDepth < maxRecursionDepthPerExecutor) loop(completedCycles + 1, newTryA, recursionDepth + 1)
								else queueForSequentialExecution(() => loop(completedCycles + 1, newTryA, 0));
							}
					}
				} catch {
					case NonFatal(cause) => onComplete(Failure(cause));
				}
			}

			if (isRunningInDoSiThEx) loop(0, tryA0, 0);
			else queueForSequentialExecution(() => loop(0, tryA0, 0));
		}
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
	 * @param buildAndCheck a function that takes the number of already completed cycles and the last task result, returning a new task that yields an `Either[Try[B], A]` indicating the next action to perform. $executedByDoSiThEx $isGuarded
	 */
	final class RepeatUntilLeft[+A, +B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(buildAndCheck: (Int, A) => Task[Either[Try[B], A]]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param executionsCounter number of already completed cycles, which consist of a task creation and its execution.
			 * @param lastTaskResult the result of the last task execution.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(executionsCounter: Int, lastTaskResult: A, recursionDepth: Int): Unit = {
				try {
					buildAndCheck(executionsCounter, lastTaskResult).attempt(true) {
						case Success(Right(a)) =>
							if (recursionDepth < maxRecursionDepthPerExecutor) loop(executionsCounter + 1, a, recursionDepth + 1)
							else queueForSequentialExecution(() => loop(executionsCounter + 1, a, 0));
						case Success(Left(tb)) =>
							onComplete(tb)
						case Failure(e) =>
							onComplete(Failure(e))
					}
				} catch {
					case NonFatal(cause) => onComplete(Failure(cause));
				}
			}

			if (isRunningInDoSiThEx) loop(0, a0, 0);
			else queueForSequentialExecution(() => loop(0, a0, 0));
		}
	}

	/** Task that, when executed, repeatedly constructs and executes tasks until it succeed or the `maxRetries` is reached.
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
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[Either[A, B]] => Unit): Unit = {
			/**
			 * @param attemptsAlreadyMade the number attempts already made.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(attemptsAlreadyMade: Int, recursionDepth: Int): Unit = {
				try {
					taskBuilder(attemptsAlreadyMade).attempt(true) {
						case Success(Right(b)) =>
							onComplete(Success(Right(b)));

						case Success(Left(a)) =>
							if (attemptsAlreadyMade >= maxRetries) {
								onComplete(Success(Left(a)));
							} else if (recursionDepth < maxRecursionDepthPerExecutor) {
								loop(attemptsAlreadyMade + 1, recursionDepth + 1);
							} else {
								queueForSequentialExecution(() => loop(attemptsAlreadyMade + 1, 0))
							}

						case Failure(cause) =>
							onComplete(Failure(cause));
					}
				} catch {
					case NonFatal(cause) => onComplete(Failure(cause))
				}
			}

			if (isRunningInDoSiThEx) loop(0, 0);
			else queueForSequentialExecution(() => loop(0, 0));
		}
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
		val task: Task[A] = new Task[A] {
			def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit = {
				def work(): Unit = thisCommitment.oResult match {
					case Some(result) =>
						onComplete(result);
					case None =>
						thisCommitment.onCompletedObservers = onComplete :: thisCommitment.onCompletedObservers;
				}

				if (isRunningInDoSiThEx) work();
				else queueForSequentialExecution(() => work());
			}
		}

		/** Provokes that the [[task]] that this [[Commitment]] promises to complete to be completed with the received `result`.
		 *
		 * @param result the result that the [[task]] this [[Commitment]] promised to complete . */
		def complete(result: Try[A])(onAlreadyCompleted: Try[A] => Unit = _ => ()): this.type = {
			queueForSequentialExecution { () =>
				oResult match {
					case None =>
						this.oResult = Some(result);
						this.onCompletedObservers.foreach(_(result));
						// la lista de observadores quedó obsoleta. Borrarla para minimizar posibilidad de memory leak.
						this.onCompletedObservers = Nil
					case Some(value) =>
						onAlreadyCompleted(value);
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
				otherTask.attempt(isRunningInDoSiThEx = false)(result => complete(result)(onAlreadyCompleted));
			this
		}
	}
}