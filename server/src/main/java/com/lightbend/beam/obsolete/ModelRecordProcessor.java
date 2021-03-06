package com.lightbend.beam.obsolete;

import com.lightbend.model.Model;
import com.lightbend.model.Modeldescriptor;
import com.lightbend.model.PMML.PMMLModel;
import com.lightbend.model.tensorflow.TensorflowModel;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;

import java.io.Serializable;

/**
 * Created by boris on 5/18/17.
 */
public class ModelRecordProcessor {

    public static class ConvertModelRecordFunction extends DoFn<KV<byte[], byte[]>, ModelToServe> {
        @ProcessElement
        public void processElement(ProcessContext c){
            KV<byte[],byte[]> input = c.element();
            try {
                // Unmarshall record
                Modeldescriptor.ModelDescriptor model = Modeldescriptor.ModelDescriptor.parseFrom(input.getValue());
                // Return it
                if(model.getMessageContentCase().equals(Modeldescriptor.ModelDescriptor.MessageContentCase.DATA)){
                    c.output(new ModelToServe(model.getName(), model.getDescription(), model.getModeltype(),
                            model.getData().toByteArray(), model.getDataType()));
                }
                else
                    System.out.println("Location based model is not yet supported");
            } catch (Throwable t) {
                // Oops
                System.out.println("Exception parsing input record" + new String(input.getValue()));
                t.printStackTrace();
            }
        }
    }

    public static class ModelToServe implements Serializable {
        private String name;
        private String description;
        private Modeldescriptor.ModelDescriptor.ModelType modelType;
        private byte[] content;
        private String dataType;

        public ModelToServe(String name, String description, Modeldescriptor.ModelDescriptor.ModelType modelType,
                            byte[] content, String dataType){
            this.name = name;
            this.description = description;
            this.modelType = modelType;
            this.content = content;
            this.dataType = dataType;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public Modeldescriptor.ModelDescriptor.ModelType getModelType() {
            return modelType;
        }

        public byte[] getContent() {
            return content;
        }

        public String getDataType() {
            return dataType;
        }

        @Override
        public String toString() {
            return "ModelToServe{" +
                    "name='" + name + '\'' +
                    ", description='" + description + '\'' +
                    ", modelType=" + modelType +
                    ", dataType='" + dataType + '\'' +
                    '}';
        }
    }

    public static class ModelFromEventsFn extends Combine.AccumulatingCombineFn<ModelToServe, ModelFromEventsFn.Accum, Model> {

        @Override
        public Accum createAccumulator() {
            return new Accum();
        }

        public class Accum implements Combine.AccumulatingCombineFn.Accumulator<ModelToServe, Accum, Model>, Serializable {

            private Model currentModel = null;

            @Override
            public void addInput(ModelToServe input) {
                System.out.println("New model" + input);
                Model model = null;
                try {
                    switch (input.modelType){
                        case PMML:
                            model = new PMMLModel(input.getContent());
                            break;
                        case TENSORFLOW:
                            model = new TensorflowModel(input.getContent());
                            break;
                        case UNRECOGNIZED:
                            System.out.println("Only PMML and Tensorflow models are currently supported");
                            break;
                    }
                    if(model != null) {
                        // Clean up current model
                        if (currentModel != null)
                            currentModel.cleanup();
                        currentModel = model;
                    }
                } catch (Throwable t) {
                    System.out.println("Failed to create model");
                    t.printStackTrace();
                }
            }

            @Override
            public void mergeAccumulator(Accum other) {
                if (other.currentModel != null)
                    currentModel = other.currentModel;
            }

            @Override
            public Model extractOutput() {
                return currentModel;
            }
        }

        @Override
        public Coder<Accum> getAccumulatorCoder(CoderRegistry registry, Coder<ModelToServe> inputCoder)
                throws CannotProvideCoderException {
            return SerializableCoder.of(Accum.class);
        }

        @Override
        public Coder<Model> getDefaultOutputCoder(CoderRegistry registry, Coder<ModelToServe> inputCoder)
                throws CannotProvideCoderException {
            return SerializableCoder.of(Model.class);
        }
    }
}