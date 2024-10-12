package readren.taskflow

import scala.util.Failure

extension [A](failure: Failure[A]) {
	def castTo[B]: Failure[B] = failure.asInstanceOf[Failure[B]]
}
