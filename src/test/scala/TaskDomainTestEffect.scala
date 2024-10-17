package readren.taskflow

import munit.ScalaCheckEffectSuite
import org.scalacheck.Arbitrary
import org.scalacheck.effect.PropF

import java.util.concurrent.Executors
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class TaskDomainTestEffect extends ScalaCheckEffectSuite {

	/** Remembers the exceptions that were unhandled in the DoSiThEx's thread.
	 * CAUTION: this variable should be accessed within the DoSiThEx thread only. */
	private val unhandledExceptions = mutable.Set.empty[String]

	private val assistant = new TaskDomain.Assistant {
		private val doSiThEx = Executors.newSingleThreadExecutor()

		override def queueForSequentialExecution(runnable: Runnable): Unit = {
			doSiThEx.execute(() => {
				try runnable.run()
				catch {
					case cause: Throwable =>
						// println(s"unhandled exception registered: $cause")
						unhandledExceptions.addOne(cause.getMessage);
						throw cause;
				}
			})
		}

		override def reportFailure(cause: Throwable): Unit = munitExecutionContext.reportFailure(cause)

	}

	private val taskDomain: TaskDomain = new TaskDomain(assistant) {}

	import taskDomain.*

	private val shared = new TaskDomainTestShared[taskDomain.type](taskDomain)
	import shared.{*, given}

	// Custom equality for Task based on the result of attempt
	private def checkEquality[A](task1: Task[A], task2: Task[A]): Future[Unit] = {
		val x = for {
			try1 <- task1.toFutureHardy()
			try2 <- task2.toFutureHardy()
		} yield try1 ==== try2
		x.map(assert(_))
	}

	// Monadic left identity law: Task.successful(x).flatMap(f) == f(x)
	test("left identity") {
		PropF.forAllF { (x: Int, f: Int => Task[Int]) =>
			val left = Task.successful(x)
				.flatMap(f)
			val right = f(x)
			checkEquality(left, right)
		}
	}

	// Monadic right identity law: m.flatMap(Task.successful) == m
	test("right identity") {
		PropF.forAllF { (m: Task[Int]) =>
			val left = m.flatMap(Task
				.successful)
			val right = m
			checkEquality(left, right)
		}
	}

	// Monadic associativity law: m.flatMap(f).flatMap(g) == m.flatMap(x => f(x).flatMap(g))
	test("associativity") {
		PropF.forAllF { (m: Task[Int], f: Int => Task[Int], g: Int => Task[Int]) =>
			val leftAssoc = m.flatMap(f)
				.flatMap(g)
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
				val leftTask = Task.failed[Int](e)
					.recover(f)
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
					task.recover { case cause => 0 }
				}.toFutureHardy()

				// Depending on the kind of exception, fatal or not, the check is very different.
				if NonFatal(exception) then {
					// When the exception is non-fatal the task should complete with a Failure containing the cause.
					future.map {
						case Failure(`exception`) => true
						case _ => false
					}
				} else {
					// When the exception is fatal it should remain unhandled, causing the doSiThEx thread to terminate. Consequently, the exception will be logged in the unhandledExceptions set, and the task and associated Future will never complete.
					// The following future will complete with `false` if the fatal exception was handled. Otherwise, it will never complete.
					val fatalWasHandled = future.map { result =>
						println(s"Was handled somehow: result=$result")
						false
					}

					// Build a task that completes with `true` as soon as the `exception` is found among the unhandled exceptions logged in the `unhandledExceptions` set; or `false` if it isn't found after 99 milliseconds.
					val fatalWasNotHandled = sleep1ms
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

					// The result of only one of the two futures, `fatalWasHandled` and `fatalWasNotHandled`, is enough to know if the check is passed. So get the result of the one that completes first.
					Future.firstCompletedOf(List(fatalWasNotHandled, fatalWasHandled))
				}

			}

			def f1[A, B](a: A): B = throw exception
			def f2[A, B, C](a: A, b: B): C = throw exception

			for {
				mapTestResult <- check(_.map(f1))
				flatMapTestResult <- check(_.flatMap(f1))
				withFilterTestResult <- check(_.withFilter(f1))
				transformTestResult <- check(_.transform(f1))
				transformWithTestResult <- check(_.transformWith(f1))
				recoverTestResult <- check(_.transform { _ => Failure(new Exception("for recover")) }.recover(f1))
				recoverWithTestResult <- check(_.transform { _ => Failure(new Exception("for recoverWith")) }.recoverWith {f1})
				repeatedHardyUntilSomeTestResult <- check(_.repeatedHardyUntilSome()(f2))
				repeatedUntilSomeTestResult <- check(_.repeatedUntilSome()(f2))
				repeatedUntilDefinedTestResult <- check(_.repeatedUntilDefined()(f2))
				repeatedWhileNoneTestResult <- check(_.repeatedWhileNone(Success(0))(f2))
				repeatedWhileUndefinedTestResult <- check(_.repeatedWhileUndefined(Success(0))(f2))
			} yield assert(
				mapTestResult
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
				&& repeatedWhileUndefinedTestResult
			)
			// TODO add a test to check if Task.andThen effect-full function is guarded.
		}
	}
}