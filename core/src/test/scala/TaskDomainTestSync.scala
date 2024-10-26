package readren.taskflow

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.compatible.Assertion
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** This suite is limited to synchronous tests and therefore it only tests part of the behavior of [[TaskDomain]].
 * All the behavior tested in this suite is also tested in the [[TaskDomainTestEffect]] suite. 
 * This suite is kept despite the [[TaskDomainTestEffect]] existence because it is easier to debug in a synchronous environment.
 * */
class TaskDomainTestSync extends AnyFreeSpec with ScalaCheckPropertyChecks with Matchers {

	private var oDomainThreadId: Option[Long] = None
	private val assistant = new TaskDomain.Assistant {
		override def queueForSequentialExecution(runnable: Runnable): Unit = {
			oDomainThreadId match {
				case None => oDomainThreadId = Some(Thread.currentThread().getId)
				case Some(domainThreadId) => assert(domainThreadId == Thread.currentThread().getId)
			}
			runnable.run()
		}

		override def reportFailure(cause: Throwable): Unit = throw cause
	}

	val taskDomain: TaskDomain = new TaskDomain(assistant) {}

	import taskDomain.*
	import Task.*

	val shared = new TaskDomainTestShared[taskDomain.type](taskDomain, true)
	import shared.{given, *}

	private def checkEquality[A](task1: Task[A], task2: Task[A]): Unit = {
		combine(task1, task2) {
			(a1, a2) => Success((a1, a2))
		}.attempt(true) {
			case Success(z) => assert(z._1 ==== z._2)
			case Failure(cause) => throw cause
		}
	}

	//	implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 1)

	"A task should satisfy monadic laws" - {

		// Left Identity: `unit(a).flatMap(f) == f(a)`
		"left identity" in {
			forAll { (a: Int, f: Int => Task[Int]) =>
				val leftTask = successful(a).flatMap(f)
				val rightTask = f(a)
				checkEquality(leftTask, rightTask)
			}
		}

		// Right Identity: `m.flatMap(a => unit(a)) == m`
		"right identity" in {
			forAll { (m: Task[Int]) =>
				val left = m.flatMap(a => successful(a))
				val right = m
				checkEquality(left, right)
			}
		}

		// Associativity: `(m.flatMap(f)).flatMap(g) == m.flatMap(a => f(a).flatMap(g))`
		"associativity" in {
			forAll { (m: Task[Int], f: Int => Task[Int], g: Int => Task[Int]) =>
				val left = m.flatMap(f).flatMap(g)
				val right = m.flatMap(a => f(a).flatMap(g))
				checkEquality(left, right)
			}
		}
	}
	// Functor: `m.map(f) == m.flatMap(a => unit(f(a)))`
	"Task can be transformed with map" in {
		forAll { (m: Task[Int], f: Int => String) =>
			val left = m.map(f)
			val right = m.flatMap(a => successful(f(a)))
			checkEquality(left, right)
		}
	}

	// Recovery: `failedTask.recover(f) == if f.isDefinedAt(e) then successful(f(e)) else failed(e)` where e is the exception thrown by failedTask
	"Task can be recovered from failure" in {
		forAll { (e: Throwable, f: PartialFunction[Throwable, Int]) =>
			whenever(NonFatal(e)) {
				val leftTask = failed[Int](e).recover(f)
				val rightTask = if f.isDefinedAt(e) then successful(f(e)) else failed(e);
				checkEquality(leftTask, rightTask)
			}
		}
	}

	// Combining Tasks: `combine(taskA, taskB)(zip) == own(() => zip(tryA, tryB))`
	"Task can be combined" in {
		forAll(intGen, intGen, Gen.oneOf(true, false), Gen.frequency((3, true), (1, false))) {
			(intA, intB, zipReturnsSuccess, zipFinishesNormally) =>
				def buildTryAndImmediateTask(i: Int): (Try[Int], Task[Int]) = {
					if i >= 0 then (Success(i), successful(i))
					else {
						val cause = new RuntimeException(i.toString)
						(Failure(cause), failed(cause))
					}
				}

				def zip(ta: Try[Int], tb: Try[Int]): Try[(Try[Int], Try[Int])] =
					if zipFinishesNormally then {
						if zipReturnsSuccess then Success((ta, tb))
						else Failure(new RuntimeException(s"finished normally for: intA=$intA, intB=$intB"))
					}
					else throw new RuntimeException(s"finished abruptly for: intA=$intA, intB=$intB")

				println(s"0) intA=$intA, intB=$intB, zipReturnsSuccess=$zipReturnsSuccess, zipFinishesNormally=$zipFinishesNormally")

				val (tryA, taskA) = buildTryAndImmediateTask(intA)
				val (tryB, taskB) = buildTryAndImmediateTask(intB)
				println(s"1) tryA = $tryA, tryB = $tryB")

				val left: Task[(Try[Int], Try[Int])] = combine(taskA, taskB)(zip)
				val right: Task[(Try[Int], Try[Int])] = own(() => zip(tryA, tryB))

				left.attempt() { resultLeft =>
					right.attempt(true) { resultRight =>
						println(s"3) resultLeft = $resultLeft, resultRight = $resultRight")
						(resultLeft, resultRight) match {
							case (Success(a), Success(b)) => assert(a._1 ==== b._1)
							case (Failure(a), Failure(b)) => assert(a ==== b)
							case _ => assert(false)
						}
					}
				}
		}
	}

	"Any task can be combined" in {
		forAll { (taskA: Task[Int], taskB: Task[Int], f: (Try[Int], Try[Int]) => Try[Int]) =>
			val combined = combine(taskA, taskB)(f)

			// do the check in all possible triggering orders
			combined.attempt() { combinedResult =>
				taskA.attempt(true) { taskAResult =>
					taskB.attempt(true) { taskBResult =>
						assert(combinedResult ==== f(taskAResult, taskBResult))
					}
				}
			}
			combined.attempt() { combinedResult =>
				taskB.attempt(true) { taskBResult =>
					taskA.attempt(true) { taskAResult =>
						assert(combinedResult ==== f(taskAResult, taskBResult))
					}
				}
			}
			taskA.attempt() { taskAResult =>
				combined.attempt(true) { combinedResult =>
					taskB.attempt(true) { taskBResult =>
						assert(combinedResult ==== f(taskAResult, taskBResult))
					}
				}
			}
			taskA.attempt() { taskAResult =>
				taskB.attempt(true) { taskBResult =>
					combined.attempt(true) { combinedResult =>
						assert(combinedResult ==== f(taskAResult, taskBResult))
					}
				}
			}
			taskB.attempt() { taskBResult =>
				combined.attempt(true) { combinedResult =>
					taskA.attempt(true) { taskAResult =>
						assert(combinedResult ==== f(taskAResult, taskBResult))
					}
				}
			}
			taskB.attempt() { taskBResult =>
				taskA.attempt(true) { taskAResult =>
					combined.attempt(true) { combinedResult =>
						assert(combinedResult ==== f(taskAResult, taskBResult))
					}
				}
			}
		}
	}
}
