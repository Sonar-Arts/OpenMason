// Private to libcenda_kernels' model module (cenda/model*.h): the ONE thread-local error message behind
// co_last_error(). Every co_* header (model.h, model_shading.h, model_import.h, model_mask.h) records its failures
// here, so co_last_error() explains the last failed co_* call whichever header it came from.
#pragma once

#include <string>

namespace cenda::model {

std::string& co_error_message();

}  // namespace cenda::model
