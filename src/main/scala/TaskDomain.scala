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
		 *
		 * All the deferred actions preformed by the [[Task]] operations are executed by calling this method unless the particular operation documentation says otherwise. That includes not only the call-back functions like `onComplete` but also all the functions, procedures, predicates, and by-name parameters they receive as.
		 * The implementation should be thread-safe. */
		def queueForSequentialExecution(runnable: Runnable): Unit

		/** The implementation should be thread-safe and report the received [[Throwable]] somehow. Preferably including a description that identifies the provider of the DoSiThEx used by [[queueForSequentialExecution]] and mentions that the error was thrown by a deferred procedure programmed by means of a [[Task]]. */
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
 * @define cuandoCompletaEjecutadaPorEsteActor El call-back `onComplete` pasado a `efectuar/ejecutar` es siempre, sin excepción, ejecutado por este actor sin envoltura `try..catch`. Esto es parte del contrato de este trait.
 * @define desdeCualquierHilo                  Este y todos los métodos de este trait pueden ser invocados desde cualquier hilo de ejecución.
 * @define ejecutadaPorEsteActor               Es importante resaltar que esta función es ejecutada por este [[Actor]]. Por ende, puede leer y modificar las variables de este actor.
 * @define estaCuidada                         Si esta función terminara abruptamente, la tarea dada terminará con la falla lanzada.
 * @define workaroundAbstractMethodError       WORKAROUND: Esta clase debería ser privada, pero se hizo pública para eludir un bug del compilador incremental (del scala-IDE) que provoca que cada vez que se toca el código y actúa el compilador incremental (no ocurre si se hace un clean) se corrompa el build y la excepción "java.lang.AbstractMethodError" sea lanzada cuando se intenta crear una instancia de la clase.
 */
class TaskDomain(assistant: TaskDomain.Assistant) { thisTaskContext =>


	/**
	 * Queues the execution of the received [[Runnable]] in this DoSiThEx (domain's single-thread executor). See [[TaskDomain.Assistant.queueForSequentialExecution]]
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
		Task.mine(runnable.run).attemptAndForget();
		assistant.queueForSequentialExecution(runnable)
	}


	/**
	 * An [[ExecutionContext]] that uses the DoSiThEx. See [[TaskDomain.assistant.queueForSequentialExecution]] */
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
	 * Immutable instances of [[Task]] adhere to monadic laws. For example, if a task `t` captures a mutable variable from the environment that affects its execution result, the equality of two supposedly equivalent expressions like {{{t.flatMap(f).flatMap(g) == t.flatMap(a => f(a).flatMap(g))}}} would depend on when the variable was mutated. This only matters if the mutation happens after execution begins and before it ends.
	 * This does not mean that [[Task]] implementations must avoid capturing mutable variables at all costs. It simply warns that if business logic requires strict adherence to monadic laws, you should ensure that the variable in question is not mutated while the task is running.
	 * If your main concern is deterministic behavior, it's sufficient that the captured mutable variable is only mutated or accessed by actions that are executed sequentially.
	 *
	 * Design note: [[Task]] is a member of [[TaskDomain]] to ensure that access to [[Task]] instances is restricted to the section of code where the owning [[TaskDomain]] is exposed.
	 *
	 * @tparam A the type of result obtained when executing this task.
	 */
	trait Task[+A] { thisTask =>

		/**
		 * The implementation must trigger the execution of this [[Task]] and then ensure the callback function `onComplete` is called within the DoSiThEx (Domain's single-thread executor) when the task's execution finishes, either normally or abnormally.
		 * This is the only primitive method of this trait. All the other derive from this one.
		 *
		 * $desdeCualquierHilo
		 *
		 * @param isRunningInDoSiThEx indicates whether the caller is certain that this method is being executed within DoSiThEx. If there is no such certainty, this parameter should be set to `false`.
		 *                            This flag is useful for those [[Task]]s whose first action can or must be executed in the DoSiThEx, as it informs them that they can immediately execute the action synchronously.
		 *                            When this is false, the call to this method is always asynchronous.
		 * @param onComplete          callback that the implementation must invoke when the execution of this task finishes. The implementation must ensure the call to this call-back occurs within the DoSiThEx, and it may assume that `onComplete` does not throw non-fatal exceptions. In other words, the received `onComplete` must either terminate normally or fatally, but never with a non-fatal exception.
		 */
		def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit;

		inline def engage(onComplete: Try[A] => Unit): Unit = attempt(false)(onComplete)

		/** Inicia la ejecución de la acción encapsulada.
		 * El future dado será completado cuando la acción encapsulada termine.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 */
		def promiseAttempt(isRunningInDoSiThEx: Boolean = false): Future[A] = {
			val promise = Promise[A]()
			attempt(isRunningInDoSiThEx)(promise.complete)
			promise.future
		}

		/** Ejecuta esta [[Task]] ignorando tanto el resultado como si terminó normal o abruptamente.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 */
		inline def attemptAndForget(isRunningInDoSiThEx: Boolean = false): Unit =
			attempt(isRunningInDoSiThEx)(_ => {})

		/** Ejecuta esta [[Task]] y si termina abruptamente llama a la función recibida.
		 * La función `manejadorError` recibida será ejecutada por este actor si la acción encapsulada termina abruptamente.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 */
		inline def attemptAndForgetHandlingErrors(isRunningInDoSiThEx: Boolean = false)(errorHandler: Throwable => Unit): Unit =
			attempt(isRunningInDoSiThEx) {
				case Failure(e) => errorHandler(e);
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
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 *
		 * @param resultTransformer función que se aplica al resultado de esta [[Task]] para determinar el resultado de la Task dada. $ejecutadaPorEsteActor $estaCuidada
		 */
		inline def transform[B](resultTransformer: Try[A] => Try[B]): Task[B] = new Transform(thisTask, resultTransformer)


		/** Composes two [[Task]]s where the second is build from the result of the first, analogous to [[Future.transformWith]].
		 * Detailed behavior: Gives a [[Task]] which, if executed, would:
		 *  - first execute this Task, and if that terminates normally:
		 *  	- second applies the function [[taskBBuilder]] to the result of step one, and if that terminates normally:
		 *  		- third executes the [[Task]] created in the second step;
		 *  - finally calls [[onComplete]] passing either:
		 *  	- if the first three steps terminated normally, the result of the task created in the 3rd step, which may be a [[Failure]].
		 *      - if any of them terminated abruptly, the exception thrown there 
		 *        The behavior of this method is analogous to [[Future.transformWith]].
		 *        The [[taskBBuilder]] is executed by the DoSiThEx.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 *
		 * @param taskBBuilder función que se aplica al resultado de esta [[Task]] para crear la [[Task]] que se ejecutará después. $ejecutadaPorEsteActor $estaCuidada
		 */
		inline def transformWith[B](taskBBuilder: Try[A] => Task[B]): Task[B] =
			new Compose(thisTask, taskBBuilder)


		/** Da una [[Task]] que al ejecutarla primero hace esta Task y luego, si  es exitoso, aplica la función `f` al resultado.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 *
		 * @param f función que transforma el resultado de la Task. $ejecutadaPorEsteActor $estaCuidada
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
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 *
		 * @param taskBBuilder función que se aplica al resultado del primer paso para crear la [[Task]] que se ejecutaría luego de ésta como parte de la Task dada. $ejecutadaPorEsteActor $estaCuidada
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
		 * */
		def withFilter(predicate: A => Boolean): Task[A] = new Task[A] {
			def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit = {
				val runnable: Runnable = () =>
					thisTask.attempt(true) {
						case s@Success(a) =>
							if (predicate(a))
								onComplete(s)
							else
								onComplete(Failure(new NoSuchElementException(s"Task filter predicate is not satisfied for $a")))

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
		 *          Es equivalente a:
		 * {{{
		 * 		transform[A] { ta =>
		 * 			try p(ta)
		 * 		catch { case NonFatal(e) => log.error(e, "..") }
		 * 	}
		 * }}}
		 *          $cuandoCompletaEjecutadaPorEsteActor
		 *          $desdeCualquierHilo
		 *
		 * @param p función parcial que representa el efecto secundario. El resultado de esta función parcial es ignorado.  $ejecutadaPorEsteActor
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
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 *
		 * @param pf función parcial que se aplica al resultado del primer paso si es fallido para determinar el resultado de la Task dada. $ejecutadaPorEsteActor $estaCuidada
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
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * $desdeCualquierHilo
		 *
		 * @param pf función parcial que se aplica al resultado del primer paso si es fallido para crear la [[Task]] que se ejecutaría luego de ésta como parte de la Task dada. $ejecutadaPorEsteActor $estaCuidada
		 */
		def recoverWith[B >: A](pf: PartialFunction[Throwable, Task[B]]): Task[B] = {
			transformWith[B] {
				case Failure(t) => pf.applyOrElse(t, (e: Throwable) => new Immediate(Failure(e)));
				case sa@Success(_) => new Immediate(sa);
			}
		}

		/** Da una nueva [[Task]] que repite esta [[Task]] hasta que esté definido el [[Option]] resultante  de aplicar la función recibida en la dupla formada por el resultado que de esta [[Task]] y la cantidad de veces que esta tarea fue ejecutada.
		 * El resultado de la [[Task]] dada es el valor contenido en el [[Option]] resultante.
		 * ADVERTENCIA: la ejecución de la tarea dada sería eterna si el resultado de esta tarea nunca da un resultado tal que al aplicarle la función recibida de un [[Option]] definido. */
		inline def repeatedUntilSome[B](maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Option[B]): Task[B] =
			new RepeatUntilSome(thisTask, maxRecursionDepthPerExecutor)(condition)


		/** Da una nueva [[Task]] que repite esta [[Task]] hasta que la [[PartialFunction]] recibida este definida en el resultado que de esta [[Task]].
		 * El resultado de la [[Task]] dada es el resultado de aplicar la [[PartialFunction]] recibida sobre el resultado de esta [[Task]].
		 * ADVERTENCIA: la ejecución de la tarea dada sería eterna si el resultado de esta tarea nunca da un resultado tal que la [[PartialFunction]] recibida este definida. */
		inline def repeatedUntilDefined[B](maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, Try[A]), B]): Task[B] =
			repeatedUntilSome(maxRecursionDepthPerExecutor)(Function.untupled(pf.lift))

		/** Returns a new [[Task]] that, when executed, repeats this [[Task]] interleaved with the task given by [[interleavedTaskBuilder]], until the result of this [[Task]] and the number of times it has been executed are such that applying the `condition` function results in a [[Right]].
		 * The interleaved task is created and executed after this task and only if the result of this task is such that applying the `condition` function results in a [[Left]].
		 * The result of the returned [[Task]] is the value contained in the [[Right]] returned by the `condition` that stopped the loop.
		 * WARNING: the execution of the returned task will never end if the result of this task always produces a result such that applying the `condition` function results in a [[Left]]. */
		def repeatedInterleavedUntilRight[B, C](maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Either[C, B])(interleavedTaskBuilder: C => Task[Unit]): Task[B] = {
			this.repeatedUntilSome(maxRecursionDepthPerExecutor) { (executionsCounter: Int, tryA: Try[A]) =>
				condition(executionsCounter, tryA) match {
					case Right(b) =>
						Some(b)

					case Left(c) =>
						interleavedTaskBuilder(c).attemptAndForgetHandlingErrors(true) {
							case NonFatal(e) => throw e
						}
						None
				}
			}
		}

		/** Da una nueva [[Task]] que hace lo siguiente: mientras la evaluación de la función `condiciónNegada` recibida de [[None]] efectúa esta tarea repetidamente.
		 * Dicho específicamente, cuando la tarea dada es ejecutada ocurre lo siguiente:
		 * - (1) Primero se evalúa la expresión `condiciónNegada(a0, 0)`. Si resulta un:
		 * -	[[Some]], la tarea dada completa exitosamente dando el contenido de dicho [[Some]].
		 * -	[[None]], efectúa esta tarea y luego evalúa la expresión `condiciónNegada(a1, 1)` donde a1 es el valor dado por esta tarea en su primera ejecución exitosa. Si resulta un:
		 * - 		[[Some]], la tarea dada completa exitosamente dando el contenido de dicho [[Some]].
		 * -		[[None]], efectúa esta tarea y luego evalúa la función `condiciónNegada(a2, 2)` donde a2 es el resultado de la segunda ejecución exitosa de esta tarea.
		 * Y así sucesivamente. */
		inline def repeatedWhileNone[S >: A, B](ts0: Try[S], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[S]) => Option[B]): Task[B] =
			new RepeatWhileNone[S, B](thisTask, ts0, maxRecursionDepthPerExecutor: Int)(condition)

		/** Da una nueva [[Task]] que hace lo siguiente: mientras la función parcial recibida no esté definida efectúa esta tarea repetidamente.
		 * Dicho específicamente, cuando la tarea dada es ejecutada ocurre lo siguiente:
		 * - (1) Primero se investiga si la función parcial esta definida en (a0, 0). Si:
		 * -	está definida, la tarea dada completa exitosamente dando el resultado de aplicarla.
		 * -	no está definida, efectúa esta tarea y luego investiga si la función parcial está definida en (a1, 1) donde a1 es el valor dado por esta tarea en su primera ejecución exitosa. Si:
		 * - 		está definida, la tarea dada completa exitosamente dando el resultado de aplicarla.
		 * -		no está definia, efectúa esta tarea y luego investiga si la función parcial esta definida en (a2, 2) donde a2 es el resultado de la segunda ejecución exitosa de esta tarea.
		 * Y así sucesivamente. */
		inline def repeatedWhileUndefined[S >: A, B](ts0: Try[S], maxRecursionDepthPerExecutor: Int = 9)(pf: PartialFunction[(Int, Try[S]), B]): Task[B] = {
			repeatedWhileNone(ts0, maxRecursionDepthPerExecutor)(Function.untupled(pf.lift));
		}

		/** Da una nueva [[Task]] que hace lo mismo que esta [[Task]], pero si falla y el predicado `p` da `true` vuelve a intentar `cantReintentos` veces.
		 * El resultado de la nueva tarea dada es el que de esta tarea en el último intento, sea exitoso o fallido.
		 */
		def retriedWhile(maxRetries: Int)(p: Throwable => Boolean): Task[A] =
			new Task[A] {
				override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit = {
					thisTask.attempt(isRunningInDoSiThEx) {
						case s@Success(_) => onComplete(s)
						case f@Failure(e) =>
							if (maxRetries <= 0)
								onComplete(f)
							else
								try {
									if (p(e))
										this.retriedWhile(maxRetries - 1)(p).attempt(isRunningInDoSiThEx = true)(onComplete)
									else
										onComplete(f)
								} catch {
									case NonFatal(nf) => onComplete(Failure(nf))
								}
					}
				}
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

		/** Creates a [[Task]] whose result is immediately given. The result of its execution is always the given value.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 */
		inline def immediate[A](tryA: Try[A]): Task[A] = new Immediate(tryA);

		/** Creates a [[Task]] whose result is immediately given and always succeeds. The result of its execution is always a [[Success]] with the given value.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 */
		inline def successful[A](a: A): Task[A] = new Immediate(Success(a));

		/** Creates a [[Task]] whose result is immediately given and always fails. The result of its execution is always a [[Failure]] with the given [[Throwable]]
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 */
		inline def failed[A](throwable: Throwable): Task[A] = new Immediate(Failure(throwable));

		/** Crea una Task que al ejecutarla programa la realización por parte de este actor de la `acción` recibida.
		 * Y luego, después que la `acción` haya sido efectuada, llama a `onComplete` pasándole:
		 * - el resultado si `acción` termina normalmente;
		 * - o `Failure(excepción)` si la `acción` termina abruptamente.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 *
		 * @param resultBuilder La acción que estará encapsulada en la Task creada. $ejecutadaPorEsteActor
		 */
		inline def own[A](resultBuilder: () => Try[A]): Task[A] = new Own(resultBuilder);

		/** Crea una Task que al ejecutarla programa la realización por parte de este actor de la `acción` recibida.
		 * Y luego, después que la `acción` haya sido efectuada, llama a `onComplete` pasándole:
		 * - `Success(resultado)` si la `acción` termina normalmente;
		 * - o `Failure(excepción)` si la `acción` termina abruptamente.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 * Equivale a {{{ propia { () => Success(accion()) } }}}
		 *
		 * @param resultBuilder La acción que estará encapsulada en la Task creada. $ejecutadaPorEsteActor
		 *
		 */
		inline def mine[A](resultBuilder: () => A): Task[A] = new Own(() => Success(resultBuilder()));

		/** Crea una Task que espera que el [[Future]] recibido haya completado, y da el resultado de dicho [[Future]].
		 * Ejecutar la [[Task]] dada provoca que el call-back `onComplete` sea llamado cuando el `future` complete pasándole el resultado del mismo.
		 *
		 * $cuandoCompletaEjecutadaPorEsteActor
		 *
		 * @param future que tendrá el resultado de la Task.
		 */
		def alien[A](future: Future[A]): Task[A] = new Alien(future);


		/** Crea una tarea que al ejecutarla:
		 *  - primero ejecuta las `tareaA` y `tareaB` en simultaneo (en oposición a esperar que termine una para ejecutar la otra)
		 *  - segundo, cuando ambas terminan, sea exitosamente o fallidamente, se aplica la función `f` al resultado de las dos tareas.
		 *  - ultimo llama a `cuandooCompleta`con:
		 *  	- la excepción lanzada por la función `f` si termina abruptamente;
		 *  	- o el resultado de f si termina normalmente
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

		/** Creates a new [[Task]] that, when executed, repeatedly constructs and executes tasks as long as the `condition` is met.
		 * Detailed behavior: Gives a new [[Task]] that, when executed, would:
		 *  - Apply the function `condition` to `(completedExecutionsCounter, tryA0)`, and if it completes:
		 *  	- Abruptly, it completes with the cause.
		 *		- Normally, returning a `Left(tryB)`, it completes with `tryB`.
		 *    	- Normally, returning a `Right(taskA)`, it executes the `taskA`, and if it completes:
		 *      	- Abruptly, it completes with the cause.
		 *      	- Normally, returning a `Failure(cause)`, it completes with that `cause`.
		 *      	- Normally, returning a `Success(tryA1)`, it goes back to the first step, replacing `tryA0` with `tryA1`.
		 *
		 * @param tryA0                        the initial iteration state.
		 * @param maxRecursionDepthPerExecutor Maximum recursion depth per executor. Once this limit is reached, the recursion continues in a new executor. The result of this [[Task]] does not depend on this parameter as long as no [[StackOverflowError]] occurs.
		 * @param condition                    function that, based on the `completedExecutionsCounter` and the iteration's state `a`, determines if the loop should end or otherwise creates the [[Task]] to execute in the next iteration.
		 * @tparam A the type of the state passed from an iteration to the next.
		 * @tparam B the type of the result of created [[Task]]
		 */
		def whileRightRepeat[A, B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Either[Try[B], Task[A]]): Task[B] =
			new WhileRightRepeat[A, B](tryA0, maxRecursionDepthPerExecutor)(condition);


		@deprecated("lo hice como ejercicio")
		def repeatUntilLeft2[A, B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Task[Either[Try[B], A]]): Task[B] =
			whileRightRepeat[Either[Try[B], A], B](tryA0.map(Right(_)), maxRecursionDepthPerExecutor)((completedExecutionsCounter, previousState) =>
				previousState match {
					case Success(Right(x)) => Right(condition(completedExecutionsCounter, Success(x)))
					case Success(Left(x)) => Left(x)
					case Failure(cause) => Right(condition(completedExecutionsCounter, Failure(cause)))
				}
			)

		/** Creates a new [[Task]] that, when executed, repeatedly constructs and executes tasks as long as the `condition` is met.
		 * ===Detailed behavior:===
		 * Gives a new [[Task]] that, when executed, would:
		 *  - Try to apply the function `condition` to `(n,a0)` where n is the number of cycles already done. If the evaluation completes:
		 *  	- Abruptly, completes with the cause.
		 *    	- Normally, returning a `task`, executes the `task` and if its result is:
		 *    		- `Failure(cause)`, completes with that `Failure(cause)`.
		 *    		- `Success(Left(tryB))`, completes with `tryB`.
		 *    		- `Success(Right(a1))`, goes back to the first step replacing `a0` with `a1`.
		 */
		inline def repeatUntilLeft[A, B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, A) => Task[Either[Try[B], A]]): Task[B] =
			new RepeatUntilLeft(a0, maxRecursionDepthPerExecutor)(condition)


		/** Crea una [[Task]], llamémosla "bucle" que al ejecutarla ejecuta la `tarea` recibida y, si el resultado es [[Left]], la vuelve ejecutar repetidamente hasta que una de tres:
		 * 		- el resultado sea [[Right]],
		 * 		- se acaben los reintentos,
		 * 		- o termine abruptamente.
		 *          El resultado de la tarea dada es el resultado que da la `tarea` en la último intento, o `Failure(excepción)` si termina abruptamente.
		 *          La `tarea` recibida siempre es ejecutada al menos una vez, incluso si la `cantReintentos` es menor a cero.
		 */
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

	}

	/** Task que al ejecutarla da como resultado el valor recibido.
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

	/** Task que al ejecutarla:
	 *  - primero programa la ejecución por parte de este actor de la `acción` recibida;
	 *  - ultimo llama a `onComplete` con:
	 *  	- el resultado de la `acción` si termina normalmente;
	 *  	- la excepción lanzada por la `acción` si termina abruptamente.
	 *
	 * @param resultBuilder builds the result of this [[Task]]. $ejecutadaPorEsteActor $estaCuidada
	 */
	final class Own[+A](resultBuilder: () => Try[A]) extends Task[A] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit = {
			def tryResultBuilder() =
				try resultBuilder()
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

	/** Task cuya acción encapsulada NO es efectuada por este [[Actor]] y cuyo resultado esta representado por un [[Future]].
	 * Ejecutar esta [[Task]] provoca que el call-back `onComplete` sea llamado cuando el `future` complete, sea exitosa o fallidamente.
	 *
	 * @param resultFuture que tendrá el resultado de la Task.
	 */
	final class Alien[+A](resultFuture: Future[A]) extends Task[A] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[A] => Unit): Unit =
			resultFuture.onComplete(onComplete)(ownSingleThreadExecutionContext)
	}


	/** [[Task]] que al ejecutarla:
	 *  - primero ejecuta la `Task` recibida;
	 *  - segundo aplica la función `f` al resultado y, si la aplicación termina normalmente:
	 *  	- tercero ejecuta la Task creada;
	 *  - último llama a `onComplete` con:
	 *  	- la excepción lanzada en el segundo paso por la operación `ctorTask` si termina abruptamente;
	 *  	- o el resultado de la ejecución del tercer paso.
	 *
	 * The [[resultTransformer]] is executed by the DoSiThEx.
	 *
	 * @param resultTransformer función que se aplica al resultado de esta [[Task]] para crear la [[Task]] que se ejecutará después. $ejecutadaPorEsteActor $estaCuidada
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
	 * @param taskA        Task que se ejecutaría antes
	 * @param taskBBuilder constructor de la Task que se ejecutaría después. Este constructor es ejecutado por este actor cuando la primer Task terminó.
	 */
	final class Compose[+A, +B](taskA: Task[A], taskBBuilder: Try[A] => Task[B]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit =
			taskA.attempt(isRunningInDoSiThEx) { resultA =>
				taskBBuilder(resultA).attempt(true)(onComplete);
			}
	}


	/** [[Task]] que al ejecutarla:
	 *  - primero ejecuta las `tareaA` y `tareaB` en simultaneo (en oposición a esperar que termine una para ejecutar la otra)
	 *  - segundo, cuando ambas tareas terminan, sea exitosamente o fallidamente, se aplica la función `f` al resultado de las dos tareas.
	 *  - ultimo llama a `cuandooCompleta`con:
	 *  	- la excepción lanzada por la función `f` si termina abruptamente;
	 *  	- o el resultado de f si termina normalmente
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
	 * @param taskA                        the task to be repeated.
	 * @param maxRecursionDepthPerExecutor Maximum recursion depth per executor. Once this limit is reached, the recursion continues in a new executor. The result of this [[Task]] does not depend on this parameter as long as no [[StackOverflowError]] occurs.
	 * @param condition                    function that decides if the loop continues or not based on:
	 *                                     the number of times this condition was already evaluated (returned a value),
	 *                                     and the result of last execution of the `taskA`.
	 * @return a new [[Task]] that, when executed, repeatedly executes the `taskA` and applies the `condition` function to the task's result until the function's result is [[Some]]. The result of this task is the contents of said [[Some]] unless any execution of the `taskA` or `condition` terminates abruptly in which case this task result is the cause.
	 * */
	final class RepeatUntilSome[+A, +B](taskA: Task[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Option[B]) extends Task[B] {

		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param conditionEvaluationsCounter number of times the evaluation of the `condition` function was completed (returning a value).
			 * @param recursionDepth              the number of recursions that may have been performed in the current executor in the worst case more synchronous scenario. */
			def loop(conditionEvaluationsCounter: Int, recursionDepth: Int): Unit = {
				taskA.attempt(isRunningInDoSiThEx = true) { tryA =>
					try {
						condition(conditionEvaluationsCounter, tryA) match {
							case Some(b) =>
								onComplete(Success(b));

							case None =>
								if (recursionDepth < maxRecursionDepthPerExecutor) {
									loop(conditionEvaluationsCounter + 1, recursionDepth + 1)
								} else {
									queueForSequentialExecution(() => loop(conditionEvaluationsCounter + 1, 0))
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

	final class RepeatWhileNone[+A, +B](taskA: Task[A], ta0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(condition: (Int, Try[A]) => Option[B]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param conditionEvaluationsCounter number of already completed evaluations of the `condition` function.
			 * @param lastTaskResult              the result of the last task execution.
			 * @param recursionDepth              the number of recursions that may have been performed in the current executor in the worst case more synchronous scenario. */
			def loop(conditionEvaluationsCounter: Int, lastTaskResult: Try[A], recursionDepth: Int): Unit = {
				try {
					condition(conditionEvaluationsCounter, lastTaskResult) match {
						case Some(b) => onComplete(Success(b))
						case None => taskA.attempt(true) { newTryA =>
							if recursionDepth < maxRecursionDepthPerExecutor then loop(conditionEvaluationsCounter + 1, newTryA, recursionDepth + 1)
							else queueForSequentialExecution(() => loop(conditionEvaluationsCounter + 1, newTryA, 0))
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
	 * Gives a new [[Task]] that, when executed, would:
	 *  - Try to apply the function `checkAndBuild` to `(n, tryA0)` where `n` is the number of already completed cycles, and if it completes:
	 *  	- Abruptly, completes with the cause.
	 *		- Normally, returning a `Left(tryB)`, completes with `tryB`.
	 *    	- Normally, returning a `Right(taskA)`, executes the `taskA` and goes back to the first step replacing `tryA0` with the result.
	 */
	final class WhileRightRepeat[+A, +B](tryA0: Try[A], maxRecursionDepthPerExecutor: Int = 9)(checkAndBuild: (Int, Try[A]) => Either[Try[B], Task[A]]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param executionsCounter number of already completed cycles, which consist of a task creation and its execution.
			 * @param lastTaskResult    the result of the last task execution.
			 * @param recursionDepth    the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(executionsCounter: Int, lastTaskResult: Try[A], recursionDepth: Int): Unit = {
				try {
					checkAndBuild(executionsCounter, lastTaskResult) match {
						case Left(tryB) =>
							onComplete(tryB);
						case Right(taskA) =>
							taskA.attempt(isRunningInDoSiThEx = true) { newTryA =>
								if (recursionDepth < maxRecursionDepthPerExecutor) loop(executionsCounter + 1, newTryA, recursionDepth + 1)
								else queueForSequentialExecution(() => loop(executionsCounter + 1, newTryA, 0));
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
	 * Gives a new [[Task]] that, when executed, would:
	 *  - Try to apply the function `buildAndCheck` to `(n, a0)` where n is the number of already completed cycles. If the evaluation completes:
	 *  	- Abruptly, completes with the cause.
	 *    	- Normally, returning a `task`, executes the `task` and if its result is:
	 *    		- `Failure(cause)`, completes with that `Failure(cause)`.
	 *    		- `Success(Left(tryB))`, completes with `tryB`.
	 *    		- `Success(Right(a1))`, goes back to the first step replacing `a0` with `a1`.
	 */
	final class RepeatUntilLeft[+A, +B](a0: A, maxRecursionDepthPerExecutor: Int = 9)(buildAndCheck: (Int, A) => Task[Either[Try[B], A]]) extends Task[B] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[B] => Unit): Unit = {
			/**
			 * @param executionsCounter number of already completed cycles, which consist of a task creation and its execution.
			 * @param lastTaskResult    the result of the last task execution.
			 * @param recursionDepth    the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
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

	/** Task that, when executed, repeatedly constructs and executes a tasks until it succeed or the `maxRetries` is reached.
	 * ===Detailed behavior:===
	 * When executed, would:
	 *  - Try to apply the function `taskBuilder` to the number of tries that were already done. If the evaluation completes:
	 *  	- Abruptly, completes with the cause.
	 *    	- Normally, executes the returned task and if the result is:
	 *    		- `Failure(cause)`, completes with `Failure(cause)`.
	 *    		- `Success(Right(b))`, completes with `Success(b)`.
	 *    		- `Success(Left(a))`, compares the retries counter against `maxRetries` and if:
	 *    			- `retriesCounter >= maxRetries`, completes with `Left(a)`
	 *    			- `retriesCounter < maxRetries`, increments the `retriesCounter` (which starts at zero) and goes back to the first step.
	 */
	final class RetryUntilRight[+A, +B](maxRetries: Int, maxRecursionDepthPerExecutor: Int = 9)(taskBuilder: Int => Task[Either[A, B]]) extends Task[Either[A, B]] {
		override def attempt(isRunningInDoSiThEx: Boolean)(onComplete: Try[Either[A, B]] => Unit): Unit = {
			/**
			 * @param retriesCounter the number retries already done.
			 * @param recursionDepth the number of recursions that may have been performed in the current executor in the worst case scenario where all calls are synchronous. */
			def loop(retriesCounter: Int, recursionDepth: Int): Unit = {
				try {
					taskBuilder(retriesCounter).attempt(true) {
						case Success(Right(b)) =>
							onComplete(Success(Right(b)));

						case Success(Left(a)) =>
							if (retriesCounter >= maxRetries) {
								onComplete(Success(Left(a)));
							} else if (recursionDepth < maxRecursionDepthPerExecutor) {
								loop(retriesCounter + 1, recursionDepth + 1);
							} else {
								queueForSequentialExecution(() => loop(retriesCounter + 1, 0))
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

		/** Provokes that the [[task]] that this [[Commitment]] promises to complete to be completed with the received `result`. */
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