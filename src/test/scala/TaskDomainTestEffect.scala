package readren.taskflow

import TaskDomain.ExceptionReport

import munit.ScalaCheckEffectSuite
import org.scalacheck.Arbitrary
import org.scalacheck.effect.PropF

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, TimeUnit}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class TaskDomainTestEffect extends ScalaCheckEffectSuite {

	/** Remembers the exceptions that were unhandled in the DoSiThEx's thread.
	 * CAUTION: this variable should be accessed within the DoSiThEx thread only. */
	private val unhandledExceptions = mutable.Set.empty[String]
	private val reportedExceptions = mutable.Set.empty[String]

	private val assistant = new TaskDomain.Assistant {
		private val doSiThEx = Executors.newSingleThreadExecutor()

		private val sequencer: AtomicInteger = new AtomicInteger(0)

		override def queueForSequentialExecution(runnable: Runnable): Unit = {
			val id = sequencer.addAndGet(1)
			// println(s"queuedForSequentialExecution: pre execute; id=$id, thread=${Thread.currentThread().getName}; runnable=$runnable")
			doSiThEx.execute(() => {
				// println(s"queuedForSequentialExecution: pre run; id=$id; thread=${Thread.currentThread().getName}")
				try {
					runnable.run()
					// println(s"queuedForSequentialExecution: run completed normally; id=$id; thread=${Thread.currentThread().getName}")
				}
				catch {
					case cause: Throwable =>
						// println(s"queuedForSequentialExecution: run completed abruptly with: $cause; id=$id; thread=${Thread.currentThread().getName}")
						unhandledExceptions.addOne(cause.getMessage);
						throw cause;
				//} finally {
				//	println(s"queuedForSequentialExecution: finally; id=$id; thread=${Thread.currentThread().getName}")
				}
			})
		}

		override def reportFailure(failure: Throwable): Unit = {
			// println(s"Reporting failure to munit: cause=${failure.getMessage}")
			munitExecutionContext.reportFailure(failure)
			doSiThEx.execute { () =>
				val exceptionToReport = if failure.isInstanceOf[ExceptionReport] then failure.getCause else failure
				reportedExceptions.addOne(exceptionToReport.getMessage)
			}
		}

	}

	private val taskDomain: TaskDomain = new TaskDomain(assistant) {}

	import taskDomain.*

	private val shared = new TaskDomainTestShared[taskDomain.type](taskDomain)
	import shared.{*, given}

	// Custom equality for Task based on the result of attempt
	private def checkEquality[A](task1: Task[A], task2: Task[A]): Future[Unit] = {
		val futureEquality = for {
			try1 <- task1.toFutureHardy()
			try2 <- task2.toFutureHardy()
		} yield {
			// println(s"$try1 ==== $try2")
			try1 ==== try2
		}
		futureEquality.map(assert(_))
	}

	private def evalNow[A](task: Task[A]): Try[A] = {
		Await.result(task.toFutureHardy(), new FiniteDuration(1, TimeUnit.MINUTES))
	}

	// Monadic left identity law: Task.successful(x).flatMap(f) == f(x)
	test("left identity") {
		PropF.forAllF { (x: Int, f: Int => Task[Int]) =>
			val sx = Task.successful(x)
			val left = Task.successful(x).flatMap(f)
			val right = f(x)
			checkEquality(left, right)
		}
	}

	// Monadic right identity law: m.flatMap(Task.successful) == m
	test("right identity") {
		PropF.forAllF { (m: Task[Int]) =>
			val left = m.flatMap(Task.successful)
			val right = m
			checkEquality(left, right)
		}
	}

	// Monadic associativity law: m.flatMap(f).flatMap(g) == m.flatMap(x => f(x).flatMap(g))
	test("associativity") {
		PropF.forAllF { (m: Task[Int], f: Int => Task[Int], g: Int => Task[Int]) =>
			val leftAssoc = m.flatMap(f).flatMap(g)
			val rightAssoc = m.flatMap(x => f(x).flatMap(g))
			checkEquality(leftAssoc, rightAssoc)
		}
	}

	// Functor: `m.map(f) == m.flatMap(a => unit(f(a)))`
	test("Task can be transformed with map") {
		PropF.forAllF { (m: Task[Int], f: Int => String) =>
			val left = m.map(f)
			val right = m.flatMap(a => Task.successful(f(a)))
			checkEquality(left, right)
		}
	}

	// Recovery: `failedTask.recover(f) == if f.isDefinedAt(e) then successful(f(e)) else failed(e)` where e is the exception thrown by failedTask
	test("Task can be recovered from failure") {
		PropF.forAllF { (e: Throwable, f: PartialFunction[Throwable, Int]) =>
			if NonFatal(e) then {
				val leftTask = Task.failed[Int](e).recover(f)
				val rightTask = if f.isDefinedAt(e) then Task.successful(f(e)) else Task.failed(e);
				checkEquality(leftTask, rightTask)
			} else Future.successful(())
		}
	}

	test("Any tasks can be combined") {
		PropF.forAllF { (taskA: Task[Int], taskB: Task[Int], f: (Try[Int], Try[Int]) => Try[Int]) =>
			val combinedTask = Task.combine(taskA, taskB)(f)

			for {
				combinedResult <- combinedTask.toFutureHardy()
				taskAResult <- taskA.toFutureHardy()
				taskBResult <- taskB.toFutureHardy()
			} yield {
				assert(combinedResult ==== f(taskAResult, taskBResult))
			}
		}
	}

	private val sleep1ms = Task.alien { () =>
		Future {
			Thread.sleep(1)
		}(global)
	}


	test("if a transformation throws an exception the task should complete with that exception if it is non-fatal, or never complete if it is fatal.") {
		PropF.forAllF { (task: Task[Int], exception: Throwable) =>

			/** Do the test for a single operation */
			def check(operation: Task[Int] => Task[Int]): Future[Boolean] = {
				// Apply the operation to the random task. The `recover` is to ensure that the task completes successfully in order to the operation is ever evaluated.
				val future = operation {
					task.recover { case cause => exception.getMessage.hashCode }
				}.toFutureHardy()

				// Build a task that completes with `true` as soon as the `exception` is found among the unhandled exceptions logged in the `unhandledExceptions` set; or `false` if it isn't found after 99 milliseconds.
				val exceptionWasNotHandled = sleep1ms
					// Check if the thread of DoSiThEx was terminated abruptly due to an unhandled exception
					.flatMap { _ =>
						Task.mine { () => unhandledExceptions.remove(exception.getMessage) }
					}
					// repeat the previous until the exception is found among the unhandled exceptions but no more than 99 times.
					.repeatedUntilSome() { (tries, theExceptionWasFoundAmongTheUnhandledOnes) =>
						if theExceptionWasFoundAmongTheUnhandledOnes then Some(Success(true))
						else if tries > 99 then Some(Success(false))
						else None
					}.toFuture()

				// Depending on the kind of exception, fatal or not, the check is very different.
				if NonFatal(exception) then {
					// When the exception is non-fatal the task should complete with a Failure containing the cause.
					val nonFatalWasHandled = future.map {
						case Failure(`exception`) => true
						case _ => false
					}
					Future.firstCompletedOf(List(exceptionWasNotHandled.map(!_), nonFatalWasHandled))
				} else {
					// When the exception is fatal it should remain unhandled, causing the doSiThEx thread to terminate. Consequently, the exception will be logged in the unhandledExceptions set, and the task and associated Future will never complete.
					// The following future will complete with `false` if the fatal exception was handled. Otherwise, it will never complete.
					val fatalWasHandled = future.map { result =>
						// println(s"Was handled somehow: result=$result")
						false
					}


					// The result of only one of the two futures, `fatalWasHandled` and `exceptionWasNotHandled`, is enough to know if the check is passed. So get the result of the one that completes first.
					Future.firstCompletedOf(List(exceptionWasNotHandled, fatalWasHandled))
				}

			}

			def f1[A, B](a: A): B = throw exception

			def f2[A, B, C](a: A, b: B): C = throw exception

			for {
				foreachTestResult <- check(_.foreach(_ => throw exception).map(_ => 0))
				mapTestResult <- check(_.map(f1))
				flatMapTestResult <- check(_.flatMap(f1))
				withFilterTestResult <- check(_.withFilter(f1))
				transformTestResult <- check(_.transform(f1))
				transformWithTestResult <- check(_.transformWith(f1))
				recoverTestResult <- check(_.transform { _ => Failure(new Exception("for recover")) }.recover(f1))
				recoverWithTestResult <- check(_.transform { _ => Failure(new Exception("for recoverWith")) }.recoverWith(f1))
				repeatedHardyUntilSomeTestResult <- check(_.repeatedHardyUntilSome()(f2))
				repeatedUntilSomeTestResult <- check(_.repeatedUntilSome()(f2))
				repeatedUntilDefinedTestResult <- check(_.repeatedUntilDefined()(f2))
				repeatedWhileNoneTestResult <- check(_.repeatedWhileNone(Success(0))(f2))
				repeatedWhileUndefinedTestResult <- check(_.repeatedWhileUndefined(Success(0))(f2))
			} yield
				assert(
					foreachTestResult
						&& mapTestResult
						&& flatMapTestResult
						&& withFilterTestResult
						&& transformTestResult
						&& transformWithTestResult
						&& recoverTestResult
						&& recoverWithTestResult
						&& repeatedHardyUntilSomeTestResult
						&& repeatedUntilSomeTestResult
						&& repeatedUntilDefinedTestResult
						&& repeatedWhileNoneTestResult
						&& repeatedWhileUndefinedTestResult,
					s"""
					   |foreach: $foreachTestResult
					   |map: $mapTestResult
					   |flatMap: $flatMapTestResult
					   |withFilter: $withFilterTestResult
					   |transform: $transformTestResult
					   |transformWith: $transformWithTestResult
					   |recover: $recoverTestResult
					   |recoverWith: $recoverWithTestResult
					   |repeatedHardyUntilSome: $repeatedHardyUntilSomeTestResult
					   |repeatedUntilSome: $repeatedUntilSomeTestResult
					   |repeatedUntilDefined: $repeatedUntilDefinedTestResult
					   |repeatedWhileNone: $repeatedWhileNoneTestResult
					   |repeatedWhileUndefined: $repeatedWhileUndefinedTestResult
				""".stripMargin
				)
			// TODO add a test to check if Task.andThen effect-full function is guarded.
		}
	}

	test("engage should either report or not catch exceptions thrown by onComplete") {
		PropF.forAllF { (task: Task[Int], exception: Throwable) =>

			/** Do the test for a single operation */
			def check[R](operation: Task[Int] => Task[R]): Future[Boolean] = {
				// Apply the operation to the random task.
				operation(task).attempt()(tryR => throw exception)

				// Build a task that completes with `true` as soon as the `exception` is found among the unhandled exceptions logged in the `unhandledExceptions` set; or `false` if it isn't found after 99 milliseconds.
				val exceptionWasNotHandledNorReported = sleep1ms
					// Check if the exception was reported or unhandled
					.flatMap { _ =>
						Task.mine { () => unhandledExceptions.remove(exception.getMessage) || reportedExceptions.remove(exception.getMessage) }
					}
					// repeat the previous until the exception is found among the unhandled exceptions but no more than 99 times.
					.repeatedUntilSome() { (tries, theExceptionWasFoundAmongTheUnhandledOnes) =>
						if theExceptionWasFoundAmongTheUnhandledOnes then {
							// println(s"The exception was found among the unhandled or reported exceptions")
							Some(Success(true))
						}
						else if tries > 99 then {
							// println(s"The exception was NOT found among the unhandled/reported exceptions after $tries retries. Waiting aborted.")
							Some(Success(false))
						}
						else {
							// println(s"The exception was NOT found among the unhandled/reported exceptions after $tries retries. Wait more time.")
							None
						}
					}.toFuture()
				exceptionWasNotHandledNorReported
			}

			val randomInt = exception.getMessage.hashCode()
			val smallNonNegativeInt = randomInt % 9
			val randomBool = (randomInt % 2) == 0
			val randomTryInt = if randomBool then Success(randomInt) else Failure(exception)
			println(s"Begin: task=$task, exception=$exception, randomInt=$randomInt, randomBool=$randomBool")

			for {
				foreachTestResult <- check(_.foreach(_ => ()))
				mapTestResult <- check(_.map(identity))
				flatMapTestResult <- check(_.flatMap(_ => task))
				withFilterTestResult <- check(_.withFilter(_ => randomBool))
				transformTestResult <- check(_.transform(identity))
				transformWithTestResult <- check(_.transformWith(_ => task))
				recoverTestResult <- check(_.recover { case x if randomBool => randomInt })
				recoverWithTestResult <- check(_.recoverWith { case x if randomBool => task })
				repeatedHardyUntilSomeTestResult <- check(_.repeatedHardyUntilSome() { (n, tryInt) => if n > smallNonNegativeInt then Some(randomTryInt) else None })
				repeatedUntilSomeTestResult <- check(_.repeatedUntilSome() { (n, i) => if n > smallNonNegativeInt then Some(randomTryInt) else None })
				repeatedUntilDefinedTestResult <- check(_.repeatedUntilDefined() { case (n, tryInt) if n > smallNonNegativeInt => tryInt })
				repeatedWhileNoneTestResult <- check(_.repeatedWhileNone(Success(0)) { (n, tryInt) => if n > smallNonNegativeInt then Some(randomTryInt) else None })
				repeatedWhileUndefinedTestResult <- check(_.repeatedWhileUndefined(Success(0)) { case (n, tryInt) if n > smallNonNegativeInt => randomInt })
			} yield
				assert(
					foreachTestResult
						&& mapTestResult
						&& flatMapTestResult
						&& withFilterTestResult
						&& transformTestResult
						&& transformWithTestResult
						&& recoverTestResult
						&& recoverWithTestResult
						&& repeatedHardyUntilSomeTestResult
						&& repeatedUntilSomeTestResult
						&& repeatedUntilDefinedTestResult
						&& repeatedWhileNoneTestResult
						&& repeatedWhileUndefinedTestResult,
					s"""
					   |foreach: $foreachTestResult
					   |map: $mapTestResult
					   |flatMap: $flatMapTestResult
					   |withFilter: $withFilterTestResult
					   |transform: $transformTestResult
					   |transformWith: $transformWithTestResult
					   |recover: $recoverTestResult
					   |recoverWith: $recoverWithTestResult
					   |repeatedHardyUntilSome: $repeatedHardyUntilSomeTestResult
					   |repeatedUntilSome: $repeatedUntilSomeTestResult
					   |repeatedUntilDefined: $repeatedUntilDefinedTestResult
					   |repeatedWhileNone: $repeatedWhileNoneTestResult
					   |repeatedWhileUndefined: $repeatedWhileUndefinedTestResult
				""".stripMargin
				)
		}
	}

}