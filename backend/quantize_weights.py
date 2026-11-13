"""Portable int8 weights with float activations (standard ONNX QDQ operators).

Dynamic activation quantization differed between native ARM and WASM kernels.
This stores matrix/embedding weights as int8 without quantizing activations.
"""
import onnx
import numpy as np
from onnx import helper, numpy_helper


def quantize_weights(source, destination):
    model=onnx.load(str(source))
    graph=model.graph
    used={name for node in graph.node if node.op_type in ('MatMul','Gemm','Gather')
          for name in node.input}
    initializers=[]
    dequantizers=[]
    count=0
    for weight in graph.initializer:
        if weight.name not in used or weight.data_type!=onnx.TensorProto.FLOAT or len(weight.dims)<2:
            initializers.append(weight)
            continue
        values=numpy_helper.to_array(weight)
        scale=np.float32(max(float(np.abs(values).max())/127,1e-12))
        quantized=np.clip(np.rint(values/scale),-127,127).astype(np.int8)
        qname,sname,zname=(weight.name+suffix for suffix in ('_int8','_scale','_zero'))
        initializers.extend([numpy_helper.from_array(quantized,qname),
                             numpy_helper.from_array(np.asarray(scale),sname),
                             numpy_helper.from_array(np.asarray(0,dtype=np.int8),zname)])
        dequantizers.append(helper.make_node('DequantizeLinear',[qname,sname,zname],[weight.name]))
        count+=1
    nodes=dequantizers+list(graph.node)
    del graph.node[:]
    graph.node.extend(nodes)
    del graph.initializer[:]
    graph.initializer.extend(initializers)
    prop=model.metadata_props.add()
    prop.key='bosc.quantization'
    prop.value='int8 weights, float32 activations; portable QDQ'
    onnx.checker.check_model(model)
    onnx.save(model,str(destination))
    return count
