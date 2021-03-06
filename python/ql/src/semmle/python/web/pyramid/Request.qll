import python

import semmle.python.security.TaintTracking
import semmle.python.web.Http
private import semmle.python.web.webob.Request
private import semmle.python.web.pyramid.View

class PyramidRequest extends BaseWebobRequest {

    PyramidRequest() {
        this = "pyramid.request"
    }

    override ClassObject getClass() {
        result = any(ModuleObject m | m.getName() = "pyramid.request").getAttribute("Request")
    }

}

/** Source of pyramid request objects */
class PyramidViewArgument extends TaintSource {

    PyramidViewArgument() {
        exists(Function view_func |
            is_pyramid_view_function(view_func) and
            this.(ControlFlowNode).getNode() = view_func.getArg(0)
        )
    }

    override predicate isSourceOf(TaintKind kind) {
        kind instanceof PyramidRequest
    }

    override string toString() {
        result = "pyramid.view.argument"
    }

}

