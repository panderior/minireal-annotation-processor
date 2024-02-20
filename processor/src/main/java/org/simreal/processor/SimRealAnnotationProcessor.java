package org.simreal.processor;

import com.example.application.kafkaserialize.KafkaTemplateSerializer;
import com.google.auto.service.AutoService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.squareup.javapoet.*;
import org.simreal.annotation.*;
import org.simreal.processor.DTO.*;
import org.springframework.kafka.core.KafkaTemplate;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

@AutoService(Processor.class)
public class SimRealAnnotationProcessor  extends AbstractProcessor
{
    // define memebre variables
    private int round=0;
    private ModelDTO modelDTO;
    private ArrayList<ParamDTO> paramDTOList;
    private ArrayList<DatabaseDTO> databaseDTOList;
    private ArrayList<ChartDTO> chartDTOList;

    private VisualDTO visualDTO;

    // define generated simulation launcher methods related variables
    private ArrayList<MethodSpec> dbMethodsList;
    private MethodSpec chartMethod;
    private MethodSpec visualMethod;
    private CodeBlock paramsCodeblock;
    private ArrayList<MethodSpec> agentDataMethodsList;
    private TypeSpec simLauncherCode;
    private Map<String, String> types_map = new HashMap<>();
    String kafka_template_field_name = "kafkaTemplate";
    String sim_param_field_name = "sim_params";
    String sim_model_var_name = "model";
    String sim_is_step_var_name = "is_step";
    String sim_launcher_class_name = "SimulationLauncher";

    // define annotation processing related variables
    private ProcessingEnvironment processingEnv;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.processingEnv = processingEnv;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes()
    {
        Set<String> set = new HashSet<>();
        set.add(SimModel.class.getName());
        set.add(SimAgent.class.getName());
        set.add(SimDB.class.getName());
        set.add(SimChart.class.getName());
        set.add(SimModelVisual.class.getName());
        set.add(SimField.class.getName());
        set.add(SimParam.class.getName());

        return set;
    }

    @Override
    public SourceVersion getSupportedSourceVersion()
    {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // initialize types map
        types_map.put("int", "Integer.parseInt");
        types_map.put("long", "Long.parseLong");
        types_map.put("double", "Double.parseDouble");
        types_map.put("float", "Float.parseFloat");
        types_map.put("boolean", "Boolean.parseBoolean");
        types_map.put("byte", "Byte.parseByte");
        types_map.put("short", "Short.parseShort");

        if(round == 0)
        {
            // generate meta-data
            try {
                makeModelMetaInfo(roundEnv);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println(e.getMessage());
                round ++;
                return false;
            }

            // process database
            dbMethodsList = new ArrayList<>();
            agentDataMethodsList = new ArrayList<>();
            generateDBMethod(roundEnv);

            // process chart
            generateChartMethod(roundEnv);

            // process visual
            generateVisualMethod(roundEnv);

            // generate code
            generateSimLauncherCode();
        }
        round ++;

        return true;
    }

    public boolean makeModelMetaInfo(RoundEnvironment roundEnv) throws Exception, IllegalArgumentException
    {
        Gson gson = new Gson();

        // Extract model annotation data
        modelDTO = new ModelDTO();
        Set<? extends Element> modelElementSet = roundEnv.getElementsAnnotatedWith(SimModel.class);
        // the usage of the SimModel annotation should be only one
        System.out.println(modelElementSet.size());
        if(modelElementSet.size() == 1)
        {
            // Element element = modelElementSet.stream().collect(Collectors.toList()).get(0);
            Element element = modelElementSet.stream().findFirst().get();
            modelDTO.setName(element.getSimpleName().toString());
            modelDTO.setClassName(ClassName.get((TypeElement) element));
        } else {
            throw new RuntimeException("Incorrect instance of @SimModel annotation. Only 1 is allowed and its required.");
        }

        System.out.println(gson.toJson(modelDTO));

        // Extract model parameters annotation data
        Set<? extends Element> paramElementSet = roundEnv.getElementsAnnotatedWith(SimParam.class);
        paramDTOList = new ArrayList<>();
        for(Element elt: paramElementSet)
        {
            ParamDTO paramDTO = new ParamDTO();
            paramDTO.setName(elt.getSimpleName().toString());
            paramDTO.setValue(elt.getAnnotation(SimParam.class).value());
            paramDTO.setType(elt.asType().toString());
            paramDTO.setIsdefault(false);
            paramDTOList.add(paramDTO);
        }
        // add steps, tick_delay default params
        ParamDTO steps_param = new ParamDTO();
        steps_param.setName("steps");
        steps_param.setValue("100");
        steps_param.setType("int");
        steps_param.setIsdefault(true);
        paramDTOList.add(steps_param);
        ParamDTO tick_delay_param = new ParamDTO();
        tick_delay_param.setName("tick_delay");
        tick_delay_param.setValue("100");
        tick_delay_param.setType("int");
        tick_delay_param.setIsdefault(true);
        paramDTOList.add(tick_delay_param);

        System.out.println(gson.toJson(paramDTOList,ArrayList.class));

        // Extract database annotation data, with agents field level annotations
        Set<String> uniqueDBNames = new HashSet<>();
        databaseDTOList = new ArrayList<>();
        Set<? extends Element> toDBElementSet = roundEnv.getElementsAnnotatedWith(SimDB.class);
        for(Element elt: toDBElementSet) {
            SimDB db_annotation = elt.getAnnotation(SimDB.class);
            String name = db_annotation.name();
            // check if the db_name already exists ... and raise and exception if it already does
            if (uniqueDBNames.contains(name)) {
                // Duplicate name found, raise an exception
                throw new IllegalArgumentException("Duplicate @SimDB annotation with the same name value: " + name);
            }
            uniqueDBNames.add(name);

            DatabaseDTO databaseDTO = new DatabaseDTO();
            databaseDTO.setTableName(db_annotation.name());
            databaseDTO.setMethodName(elt.getSimpleName().toString());
            // get the Agent class related to the database persisting method
            TypeMirror type = ((ExecutableElement) elt).getReturnType();
            TypeMirror agentGenericType = ((DeclaredType) type).getTypeArguments().get(0);
            Element agentType = ((DeclaredType) agentGenericType).asElement();
            databaseDTO.setBoundedAgentName(ClassName.get((TypeElement)agentType));

            List<FieldDTO> simLogFields = agentType.getEnclosedElements().stream()
                    .filter(x -> x.getKind() == ElementKind.FIELD)
                    .filter(x -> x.getAnnotation(SimField.class) != null)
                    .map(x->{
                        FieldDTO simLogField = new FieldDTO();
                        simLogField.setName(x.getSimpleName().toString());
                        simLogField.setType(x.asType().toString());
                        return simLogField;
                    }).collect(Collectors.toList());
            databaseDTO.setBoundedAgentFields(simLogFields);

            databaseDTOList.add(databaseDTO);
        }
        System.out.println(gson.toJson(databaseDTOList,ArrayList.class));

        // Extract Charting annotation data
        Set<String> uniqueChartNames = new HashSet<>();
        chartDTOList = new ArrayList<>();
        Set<? extends Element> toChartElementSet = roundEnv.getElementsAnnotatedWith(SimChart.class);
        for(Element elt: toChartElementSet) {
            SimChart chart_annotation = elt.getAnnotation(SimChart.class);
            String name = chart_annotation.name();
            // check if the chart_name already exists ... and raise and exception if it already does
            if (uniqueChartNames.contains(name)) {
                // Duplicate name found, raise an exception
                throw new IllegalArgumentException("Duplicate @SimChart annotation with the same name value: " + name);
            }
            uniqueChartNames.add(name);

            ChartDTO chartDTO = new ChartDTO();

            chartDTO.setChartName(elt.getAnnotation(SimChart.class).name());
            chartDTO.setMethodName(elt.getSimpleName().toString());
            chartDTOList.add(chartDTO);
        }
        System.out.println(gson.toJson(chartDTOList, ArrayList.class));

        // Extract Visualizing annotation data
        visualDTO = new VisualDTO();
        Set<? extends Element> visualElementSet = roundEnv.getElementsAnnotatedWith(SimModelVisual.class);
        // the usage of the SimModel annotation should be only one
        if(visualElementSet.size() <= 1)
        {
            Element element = visualElementSet.stream().findFirst().get();
            visualDTO.setModelMethodName(element.getSimpleName().toString());
            // get the Agent class related to the visual method
            TypeMirror type = ((ExecutableElement) element).getReturnType();
            TypeMirror agentGenericType = ((DeclaredType) type).getTypeArguments().get(0);
            Element agentType = ((DeclaredType) agentGenericType).asElement();
            visualDTO.setBoundedAgentName(ClassName.get((TypeElement)agentType));
            // get the Agent UI method name
            String agentMethodName = agentType.getEnclosedElements().stream()
                    .filter(x -> x.getKind() == ElementKind.METHOD)
                    .filter(x -> x.getAnnotation(SimAgentVisual.class) != null)
                    .map(x -> {
                        return x.getSimpleName().toString();
                    })
                    .collect(Collectors.toList()).get(0);
            visualDTO.setAgentMethodName(agentMethodName);
        } else {
            throw new Exception("SimVisual Annotation should be only 1");
        }
        System.out.println(gson.toJson(visualDTO));

        // Write metadata to file
        MetaModelDTO metaModelDTO = new MetaModelDTO();
        metaModelDTO.setModelDTO(modelDTO);
        metaModelDTO.setDbDTOList(databaseDTOList);
        metaModelDTO.setParamDTOList(paramDTOList);
        metaModelDTO.setChartDTOList(chartDTOList);
        metaModelDTO.setVisualDTO(visualDTO);

        FileObject object = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT,"","metaData.json");
        Writer writer = object.openWriter();
        gson.toJson(metaModelDTO, writer);
        writer.close();

        System.out.println(object.toUri().toString());

        return true;
    }

    public boolean generateDBMethod(RoundEnvironment roundEnv)
    {
        // String send_agt_data_method_name = String.format("%sSendData", dbDTO.getBoundedAgentName().simpleName().toString());
        String send_agt_data_method_name = "sendAgentsData";
        MethodSpec.Builder sendAgentsDataMethod = MethodSpec.methodBuilder(send_agt_data_method_name)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(modelDTO.getClassName(), "model");

        for(DatabaseDTO dbDTO: databaseDTOList)
        {
            // generate get agent data method
            CodeBlock.Builder getAgentCode = CodeBlock.builder();
            getAgentCode.addStatement("$T<$T, $T> sim_data = new $T<>()", Map.class, String.class, Object.class, LinkedHashMap.class);
//            getAgentCode.addStatement("sim_data.put(\"step\", model.schedule.getSteps())");
            ArrayList<FieldDTO> agt_db_fields = (ArrayList<FieldDTO>) dbDTO.getBoundedAgentFields();
            // define  parametrized map type for agent data method
            ParameterizedTypeName parameterized_map_type = ParameterizedTypeName.get(
                    ClassName.get(Map.class),
                    ClassName.get(String.class),
                    ClassName.get(Object.class));
            for(FieldDTO temp_agt_field: agt_db_fields)
            {
                // Capitalize the first letter of the agent name using StringBuilder
                StringBuilder agt_name_sb = new StringBuilder(temp_agt_field.getName());
                agt_name_sb.setCharAt(0, Character.toUpperCase(agt_name_sb.charAt(0)));
//                String field_name_capitalized = temp_agt_field.getName().substring(0, 1).toUpperCase() + temp_agt_field.getName().substring(1);
                String field_name_capitalized = agt_name_sb.toString();
                getAgentCode.addStatement("sim_data.put(\"$L\", agent.get$L())", temp_agt_field.getName(), field_name_capitalized);
            }
            getAgentCode.addStatement("return sim_data");

            String agt_data_method_name = String.format("get%sData", dbDTO.getBoundedAgentName().simpleName().toString());
            MethodSpec getAgentDataMethod = MethodSpec.methodBuilder(agt_data_method_name).build();
            // check if the agent data getter method is already defined
            boolean is_agt_data_method_exist = false;
            for(MethodSpec temp_agt_method: agentDataMethodsList)
            {
                if(temp_agt_method.name.toString().equals(agt_data_method_name))
                {
                    is_agt_data_method_exist = true;
                    getAgentDataMethod = temp_agt_method;
                    break;
                }
            }
            // if the agent data getter method is not already defined, define it
            if(!is_agt_data_method_exist)
            {
                getAgentDataMethod = MethodSpec.methodBuilder(agt_data_method_name)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(parameterized_map_type)
                        .addParameter(modelDTO.getClassName(), "model")
                        .addParameter(dbDTO.getBoundedAgentName(), "agent")
                        .addCode(getAgentCode.build())
                        .build();

                agentDataMethodsList.add(getAgentDataMethod);
            }

            // loop through db agent method and send data to kafka
            CodeBlock.Builder send_agt_data_code = CodeBlock.builder();
            send_agt_data_code.beginControlFlow("for ($T temp_agt: model.$L())", dbDTO.getBoundedAgentName(), dbDTO.getMethodName());
            send_agt_data_code.addStatement("$L.send(\"db\", \"$L\", $N($L, temp_agt))",
                    kafka_template_field_name,
                    dbDTO.getTableName(),
                    getAgentDataMethod,
                    sim_model_var_name);
            send_agt_data_code.endControlFlow();

            sendAgentsDataMethod.addComment("send $L agent's data", dbDTO.getBoundedAgentName().simpleName())
                            .addCode(send_agt_data_code.build());
//            dbMethodsList.add(sendAgentDataMethod);
        }
        dbMethodsList.add(sendAgentsDataMethod.build());
        return true;
    }

    public boolean generateChartMethod(RoundEnvironment roundEnv)
    {
        MethodSpec.Builder temp_chart_method = MethodSpec.methodBuilder("sendChartingData")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(modelDTO.getClassName(), "model")
                .returns(void.class);
        for(ChartDTO temp_chart_dto: chartDTOList)
        {
            temp_chart_method.addStatement("$L.send(\"chart\", \"$L\", $L.$L())",
                    kafka_template_field_name,
                    temp_chart_dto.chartName,
                    sim_model_var_name,
                    temp_chart_dto.methodName);
        }

        chartMethod = temp_chart_method.build();
        return true;
    }

    public boolean generateSimLauncherCode()
    {
        // define Kafka template parametrized type
        ParameterizedTypeName kafka_template_type = ParameterizedTypeName.get(
                ClassName.get(KafkaTemplate.class),
                ClassName.get(String.class),
                ClassName.get(Object.class));
        // define the member variables for the simulation launcher class
        FieldSpec kafka_template_field = FieldSpec.builder(kafka_template_type, kafka_template_field_name)
                .addModifiers(Modifier.PRIVATE)
                .build();

        ParameterizedTypeName sim_params_map_type = ParameterizedTypeName.get(
                ClassName.get(HashMap.class),
                ClassName.get(String.class),
                ClassName.get(Object.class));
        FieldSpec sim_params_field = FieldSpec.builder(sim_params_map_type, sim_param_field_name)
                .addModifiers(Modifier.PRIVATE)
                .build();

        // define run method code block
        generateSimParamsRunMethodCode();
        // define the simulation launcher constructor
        MethodSpec simConstructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(kafka_template_type, kafka_template_field_name)
                .addParameter(sim_params_map_type, sim_param_field_name)
                .addStatement("this.$L = $L", kafka_template_field_name, kafka_template_field_name)
                .addStatement("this.$L = $L", sim_param_field_name, sim_param_field_name)
                .build();
        // define run method
        MethodSpec runMethod = MethodSpec.methodBuilder("run")
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addAnnotation(Override.class)
                .addComment("setup simulation and model arguments")
                .addCode(generateSimParamsRunMethodCode())
                .addCode(generateDataSendingRunMethodCode())
                .build();

        // define main method
        MethodSpec mainMethod = MethodSpec.methodBuilder("main")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(void.class)
                .addParameter(String[].class, "args")
                .addCode(generateMainMethodCode())
                .build();

        simLauncherCode = TypeSpec.classBuilder(sim_launcher_class_name)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(Runnable.class)
                .addField(kafka_template_field)
                .addField(sim_params_field)
                .addMethod(simConstructor)
                .addMethod(runMethod)
                .addMethod(mainMethod)
                .addMethods(agentDataMethodsList)
                .addMethods(dbMethodsList)
                .addMethod(chartMethod)
                .addMethod(visualMethod)
                .build();

        try
        {
            JavaFile.builder(modelDTO.getClassName().packageName(), simLauncherCode)
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "ERROR : " + e);
            throw new RuntimeException(e);
        }

        return true;
    }

    // a helper methods to generate different code parts of the run method and main method
    private CodeBlock generateSimParamsRunMethodCode()
    {
        CodeBlock.Builder run_method_sim_params_code = CodeBlock.builder();
        for(ParamDTO temp_sim_param: paramDTOList)
        {
            String type_parser_code = types_map.getOrDefault(temp_sim_param.getType(), "String.valueOf");
            run_method_sim_params_code.addStatement("$L $L = $L($L.containsKey(\"$L\") ? $L.get(\"$L\").toString() : \"$L\")",
                    temp_sim_param.getType(),
                    temp_sim_param.getName(),
                    type_parser_code,
                    sim_param_field_name,
                    temp_sim_param.getName(),
                    sim_param_field_name,
                    temp_sim_param.getName(),
                    temp_sim_param.getValue()
            );
        }
        return run_method_sim_params_code.build();
    }

    private CodeBlock generateDataSendingRunMethodCode()
    {
        ArrayList<String> models_args_list = new ArrayList<>();
        paramDTOList.forEach(temp_param -> {
            if(!temp_param.isdefault)
                models_args_list.add(temp_param.getName());
        });
        String models_args_str = String.join(", ", models_args_list);
        CodeBlock.Builder run_method_data_sending_code = CodeBlock.builder();
        run_method_data_sending_code.addStatement("$T $L = new $T($L)",
                modelDTO.getClassName(),
                sim_model_var_name,
                modelDTO.getClassName(),
                models_args_str
        );

        run_method_data_sending_code.beginControlFlow("do");
        run_method_data_sending_code.addStatement("$L.send(\"tick\", \"$L\", $L.schedule.getSteps())",
                kafka_template_field_name,
                "ui_token",
                "model");
        run_method_data_sending_code.addStatement("// send database data");
        for(MethodSpec temp_data_method: dbMethodsList)
        {
            run_method_data_sending_code.addStatement("$L($L)", temp_data_method.name, sim_model_var_name);
        }
        run_method_data_sending_code.addStatement("// send charting data");
        run_method_data_sending_code.addStatement("$L($L)", chartMethod.name, sim_model_var_name);
        run_method_data_sending_code.addStatement("// send visualization data");
        run_method_data_sending_code.addStatement("$L($L)", visualMethod.name, sim_model_var_name);
        run_method_data_sending_code.addStatement("$T $L = $L.schedule.step($L)", boolean.class, sim_is_step_var_name, sim_model_var_name, sim_model_var_name);
        run_method_data_sending_code.addStatement("if (!$L) break", sim_is_step_var_name);


        run_method_data_sending_code.beginControlFlow("try");
        run_method_data_sending_code.addStatement("$T.sleep($L)", Thread.class, "tick_delay");
        run_method_data_sending_code.nextControlFlow("catch($T e)", InterruptedException.class);
        run_method_data_sending_code.addStatement("throw new $T(e)", RuntimeException.class);
        run_method_data_sending_code.endControlFlow();

        run_method_data_sending_code.endControlFlow();
        run_method_data_sending_code.addStatement("while ($L.schedule.getSteps() < steps)", sim_model_var_name);

        return run_method_data_sending_code.build();
    }

    private CodeBlock generateMainMethodCode()
    {
        // define used var names
        String ois_instance_name = "ois";
        String kafka_template_serializer_instance_name = "kafkaTemplateSerializer";
        String kafka_template_deserialized_name  = "kafkaTemplate_deserialized";
        String model_params_deserialized_name = "modelParams_deserialized";
        String hash_type_name = "hash_type";
        String runnable_model_obj_name = "model_obj";
        String model_thread_name = "model_thread";

        CodeBlock.Builder main_method_code = CodeBlock.builder();
        main_method_code.beginControlFlow("try");
        main_method_code.addStatement("// get kafka template from args");
        main_method_code.addStatement("$T $L = new $T(new $T(args[0]))",
                ObjectInputStream.class,
                ois_instance_name,
                ObjectInputStream.class,
                FileInputStream.class);
        main_method_code.addStatement("$T $L = ($T) $L.readObject()",
                KafkaTemplateSerializer.class,
                kafka_template_serializer_instance_name,
                KafkaTemplateSerializer.class,
                ois_instance_name);
        main_method_code.addStatement("ois.close()");
        // define Kafka template parametrized type
        ParameterizedTypeName kafka_template_type = ParameterizedTypeName.get(
                ClassName.get(KafkaTemplate.class),
                ClassName.get(String.class),
                ClassName.get(Object.class));
        main_method_code.addStatement("$T $L = $L.getKafkaTemplate()",
                kafka_template_type,
                kafka_template_deserialized_name,
                kafka_template_serializer_instance_name);
        // define embedded class types
        ParameterizedTypeName hash_map_type = ParameterizedTypeName.get(
                ClassName.get(HashMap.class),
                ClassName.get(String.class),
                ClassName.get(Object.class));
        ParameterizedTypeName type_token_type = ParameterizedTypeName.get(
                ClassName.get(TypeToken.class),
                hash_map_type);
        main_method_code.addStatement("// get model parameters from args");
        main_method_code.addStatement("$T $L = new $T(){}.getType()",
                Type.class,
                hash_type_name,
                type_token_type);
        main_method_code.addStatement("$T $L = args.length > 1 ? new $T().fromJson(args[1], $L) : new $T<>()",
                hash_map_type,
                model_params_deserialized_name,
                Gson.class,
                hash_type_name,
                HashMap.class);
        main_method_code.addStatement("System.out.println($L.toString())", model_params_deserialized_name);

        main_method_code.addStatement("// run simulation");
        main_method_code.addStatement("$T $L = new $L($L, $L)",
                Runnable.class,
                runnable_model_obj_name,
                sim_launcher_class_name,
                kafka_template_deserialized_name,
                model_params_deserialized_name);
        main_method_code.addStatement("$T $L = new $T($L)",
                Thread.class,
                model_thread_name,
                Thread.class,
                runnable_model_obj_name);
        main_method_code.addStatement("$L.start()", model_thread_name);
        main_method_code.nextControlFlow("catch($T | $T e)", IOException.class, ClassNotFoundException.class);
        main_method_code.addStatement("throw new $T(e)", RuntimeException.class);
        main_method_code.endControlFlow();

        return main_method_code.build();

    }

    private boolean generateVisualMethod(RoundEnvironment roundEnv)
    {
        String send_visual_data_method_name = "sendVisualData";
        MethodSpec.Builder tempSendVisualMethod = MethodSpec.methodBuilder(send_visual_data_method_name)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(modelDTO.getClassName(), "model");
        tempSendVisualMethod.addStatement("int agents_popln = model.$L().size()", visualDTO.getModelMethodName());
        tempSendVisualMethod.beginControlFlow("for ($T temp_agt: model.$L())", visualDTO.getBoundedAgentName(), visualDTO.getModelMethodName());
        tempSendVisualMethod.addStatement("$L.send(\"visuals\", $L.valueOf(agents_popln), temp_agt.$L())",
                kafka_template_field_name,
                String.class.getSimpleName(),
                visualDTO.getAgentMethodName());
        tempSendVisualMethod.endControlFlow();
        visualMethod = tempSendVisualMethod.build();

        return true;
    }

}
