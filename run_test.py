from voyager import Voyager

MODEL = "openai/gpt-5"

voyager = Voyager(
    mc_port=25565,
    server_port=3000,
    action_agent_model_name=MODEL,
    curriculum_agent_model_name=MODEL,
    curriculum_agent_qa_model_name=MODEL,
    critic_agent_model_name=MODEL,
    skill_manager_model_name=MODEL,
    max_iterations=2,
    ckpt_dir="ckpt",
)

voyager.learn()
