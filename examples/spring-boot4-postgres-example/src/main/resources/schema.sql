drop table if exists book_categories;
drop table if exists review;
drop table if exists chapter;
drop table if exists book;
drop table if exists category;
drop table if exists author;

create table author (
    id bigserial primary key,
    name text not null,
    email text
);

create table book (
    id bigserial primary key,
    title text not null,
    isbn text,
    publication_year int not null,
    author_id bigint references author (id),
    width_cm float8 not null,
    height_cm float8 not null,
    weight_grams int not null
);

create table review (
    id bigserial primary key,
    rating int not null,
    comment text not null,
    book_id bigint references book (id)
);

create table chapter (
    id bigserial primary key,
    sequence int not null,
    title text not null,
    book_id bigint references book (id)
);

create table category (
    id bigserial primary key,
    name text not null
);

create table book_categories (
    book_id bigint not null references book (id),
    category_id bigint not null references category (id),
    primary key (book_id, category_id)
);
