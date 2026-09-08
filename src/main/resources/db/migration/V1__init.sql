-- IngredientCatelog schema v1: ingredients, reusable tags, and the join between them.
-- Matches the JPA mappings; Hibernate runs in validate mode against this.

create table ingredient (
    id   bigint       not null,
    name varchar(255) not null,
    primary key (id),
    constraint uk_ingredient_name unique (name)
) engine = InnoDB;

create table tag (
    id          bigint       not null,
    name        varchar(100) not null,
    namespace   varchar(60),
    description varchar(500),
    primary key (id),
    constraint uk_tag_name unique (name)
) engine = InnoDB;

create index ix_tag_namespace on tag (namespace);

create table ingredient_tag (
    ingredient_id bigint not null,
    tag_id        bigint not null,
    primary key (ingredient_id, tag_id),
    constraint fk_ingredient_tag__ingredient foreign key (ingredient_id) references ingredient (id),
    constraint fk_ingredient_tag__tag        foreign key (tag_id)        references tag (id)
) engine = InnoDB;

-- @GeneratedValue (AUTO) on MySQL uses one table per entity as the id source.
create table ingredient_seq (next_val bigint) engine = InnoDB;
insert into ingredient_seq (next_val) values (1);

create table tag_seq (next_val bigint) engine = InnoDB;
insert into tag_seq (next_val) values (1);
